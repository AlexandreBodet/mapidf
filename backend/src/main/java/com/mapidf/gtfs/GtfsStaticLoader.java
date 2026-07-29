package com.mapidf.gtfs;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import com.mapidf.data.entity.Branch;
import com.mapidf.data.entity.Route;
import com.mapidf.data.entity.Stop;
import com.mapidf.data.entity.StopTime;
import com.mapidf.data.enums.TransportMode;
import com.mapidf.data.repositories.BranchRepository;
import com.mapidf.data.repositories.RouteRepository;
import com.mapidf.data.repositories.StopRepository;
import com.mapidf.data.repositories.StopTimeRepository;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.PrecisionModel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Charge un GTFS statique (zip IDFM potentiellement volumineux : stop_times.txt peut peser
 * plusieurs centaines de Mo une fois décompressé) en ne conservant en mémoire QUE les lignes
 * utiles à la route demandée. Aucun fichier n'est jamais chargé intégralement en RAM :
 * chaque .txt est parcouru en flux (BufferedReader + Commons CSV en mode itérateur) et filtré
 * au fil de l'eau.
 */
@Slf4j
@Component
@AllArgsConstructor
public class GtfsStaticLoader {

    private static final int SRID = 4326;
    private static final int BATCH_SIZE = 1000;
    private static final CSVFormat CSV_FORMAT = CSVFormat.DEFAULT.builder()
        .setHeader().setSkipHeaderRecord(true).setTrim(true).build();

    private final RouteRepository routeRepository;
    private final StopRepository stopRepository;
    private final BranchRepository branchRepository;
    private final StopTimeRepository stopTimeRepository;
    private final GeometryFactory geometryFactory = new GeometryFactory(new PrecisionModel(), SRID);

    @Transactional
    public void loadFromZip(InputStream zipIn, String routeId) throws IOException {
        Path tempZip = Files.createTempFile("gtfs-static-", ".zip");
        try {
            Files.copy(zipIn, tempZip, StandardCopyOption.REPLACE_EXISTING);
            loadFromZipFile(tempZip, routeId);
        } finally {
            Files.deleteIfExists(tempZip);
        }
    }

    private void loadFromZipFile(Path zipPath, String routeId) throws IOException {
        try (ZipFile zipFile = new ZipFile(zipPath.toFile())) {
            stopTimeRepository.deleteAllInBatch();
            branchRepository.deleteAllInBatch();
            stopRepository.deleteAllInBatch();
            routeRepository.deleteAllInBatch();

            RouteInfo routeInfo = findRoute(zipFile, routeId);
            TripsParseResult tripsParsed = parseTrips(zipFile, routeId);
            LineDescriptor descriptor =
                LineDescriptor.of(routeId, routeInfo.shortName(), routeInfo.color(), TransportMode.METRO);

            Route route = routeRepository.save(Route.builder()
                .gtfsId(routeId)
                .shortName(routeInfo.shortName())
                .color(descriptor.color())
                .mode(TransportMode.METRO.name())
                .siriLineRef(descriptor.siriLineRef())
                .build());

            Map<String, Branch> branchesByTripId = persistBranches(
                route, tripsParsed.tripRows(), buildLongestShape(zipFile, tripsParsed.shapeIds()));

            StopTimesParseResult stopTimesParsed = parseStopTimes(zipFile, branchesByTripId);
            Map<String, Stop> stopsByGtfsId = persistStops(zipFile, stopTimesParsed.stopIds());

            persistStopTimes(branchesByTripId, stopsByGtfsId, stopTimesParsed.rows());
        }
    }

    private RouteInfo findRoute(ZipFile zipFile, String routeId) throws IOException {
        try (CSVParser parser = openCsv(zipFile, "routes.txt")) {
            for (CSVRecord r : parser) {
                if (r.get("route_id").equals(routeId)) {
                    return new RouteInfo(r.get("route_short_name"), safe(r, "route_color"));
                }
            }
        }
        throw new IllegalStateException("route absente: " + routeId);
    }

    private TripsParseResult parseTrips(ZipFile zipFile, String routeId) throws IOException {
        List<TripRow> tripRows = new ArrayList<>();
        Set<String> shapeIds = new HashSet<>();
        try (CSVParser parser = openCsv(zipFile, "trips.txt")) {
            for (CSVRecord r : parser) {
                if (!r.get("route_id").equals(routeId)) {
                    continue;
                }
                String shapeId = safe(r, "shape_id");
                if (shapeId != null) {
                    shapeIds.add(shapeId);
                }
                tripRows.add(new TripRow(
                    r.get("trip_id"),
                    safe(r, "trip_headsign"),
                    Short.parseShort(safe(r, "direction_id", "0"))));
            }
        }
        if (tripRows.isEmpty()) {
            throw new IllegalStateException("aucun trip pour la route: " + routeId);
        }
        if (shapeIds.isEmpty()) {
            throw new IllegalStateException("aucun shape_id pour la route: " + routeId);
        }
        return new TripsParseResult(tripRows, shapeIds);
    }

    /**
     * Port mécanique sur Branch de la logique existante : un tracé unique (le plus long) et
     * une branche par sens, portée par la première course rencontrée dans ce sens. La tâche 5
     * remplace cette sélection par la couverture gloutonne des tracés réels.
     *
     * @return la branche indexée par le {@code trip_id} de sa course représentative — c'est la
     *     clé qui permet ensuite de ne retenir que les {@code stop_times} de cette course.
     */
    private Map<String, Branch> persistBranches(Route route, List<TripRow> tripRows, LineString shape) {
        Map<Short, TripRow> representativeByDirection = new HashMap<>();
        for (TripRow row : tripRows) {
            representativeByDirection.putIfAbsent(row.direction(), row);
        }
        Map<String, Branch> branchesByTripId = new HashMap<>();
        for (Map.Entry<Short, TripRow> entry : representativeByDirection.entrySet()) {
            Branch branch = branchRepository.save(Branch.builder()
                .route(route)
                .gtfsShapeId(route.getGtfsId() + ":" + entry.getKey())
                .representativeTrip(entry.getValue().tripId())
                .direction(entry.getKey())
                .terminusName(entry.getValue().headsign())
                .geom(shape)
                .build());
            branchesByTripId.put(entry.getValue().tripId(), branch);
        }
        return branchesByTripId;
    }

    private StopTimesParseResult parseStopTimes(ZipFile zipFile, Map<String, Branch> branchesByTripId)
        throws IOException {
        List<StopTimeRow> rows = new ArrayList<>();
        Set<String> stopIds = new HashSet<>();
        try (CSVParser parser = openCsv(zipFile, "stop_times.txt")) {
            for (CSVRecord r : parser) {
                String tripId = r.get("trip_id");
                if (!branchesByTripId.containsKey(tripId)) {
                    continue;
                }
                String stopId = r.get("stop_id");
                rows.add(new StopTimeRow(
                    tripId,
                    stopId,
                    Integer.parseInt(r.get("stop_sequence")),
                    toSeconds(r.get("arrival_time")),
                    toSeconds(r.get("departure_time"))));
                stopIds.add(stopId);
            }
        }
        return new StopTimesParseResult(rows, stopIds);
    }

    private Map<String, Stop> persistStops(ZipFile zipFile, Set<String> stopIds) throws IOException {
        List<Stop> stopsToSave = new ArrayList<>();
        try (CSVParser parser = openCsv(zipFile, "stops.txt")) {
            for (CSVRecord r : parser) {
                String stopId = r.get("stop_id");
                if (!stopIds.contains(stopId)) {
                    continue;
                }
                stopsToSave.add(Stop.builder()
                    .gtfsId(stopId)
                    .name(r.get("stop_name"))
                    .parentStation(safe(r, "parent_station"))
                    .geom(geometryFactory.createPoint(new Coordinate(
                        Double.parseDouble(r.get("stop_lon")), Double.parseDouble(r.get("stop_lat")))))
                    .build());
            }
        }
        Map<String, Stop> stopsByGtfsId = new HashMap<>();
        for (Stop stop : saveAllInBatches(stopRepository, stopsToSave)) {
            stopsByGtfsId.put(stop.getGtfsId(), stop);
        }
        return stopsByGtfsId;
    }

    private void persistStopTimes(Map<String, Branch> branchesByTripId, Map<String, Stop> stopsByGtfsId,
                                   List<StopTimeRow> rows) {
        List<StopTime> stopTimesToSave = rows.stream()
            .map(row -> StopTime.builder()
                .branch(branchesByTripId.get(row.tripId()))
                .stop(stopsByGtfsId.get(row.stopId()))
                .stopSequence(row.stopSequence())
                .arrivalSec(row.arrivalSec())
                .departureSec(row.departureSec())
                .build())
            .toList();
        saveAllInBatches(stopTimeRepository, stopTimesToSave);
    }

    /**
     * Une route référence souvent plusieurs shapes (services partiels, terminus divers).
     * On retient le tracé le plus long : c'est celui qui couvre la ligne de bout en bout,
     * sinon les arrêts hors de son emprise se projettent tous sur son extrémité (fraction 0)
     * et les véhicules s'y empilent.
     */
    private LineString buildLongestShape(ZipFile zipFile, Set<String> shapeIds) throws IOException {
        Map<String, List<ShapePoint>> pointsByShape = new HashMap<>();
        try (CSVParser parser = openCsv(zipFile, "shapes.txt")) {
            for (CSVRecord r : parser) {
                String shapeId = r.get("shape_id");
                if (!shapeIds.contains(shapeId)) {
                    continue;
                }
                pointsByShape.computeIfAbsent(shapeId, k -> new ArrayList<>()).add(new ShapePoint(
                    Integer.parseInt(r.get("shape_pt_sequence")),
                    Double.parseDouble(r.get("shape_pt_lon")),
                    Double.parseDouble(r.get("shape_pt_lat"))));
            }
        }
        LineString longest = null;
        String longestId = null;
        for (Map.Entry<String, List<ShapePoint>> entry : pointsByShape.entrySet()) {
            List<ShapePoint> points = new ArrayList<>(entry.getValue());
            points.sort(Comparator.comparingInt(ShapePoint::sequence));
            Coordinate[] coordinates = points.stream()
                .map(p -> new Coordinate(p.lon(), p.lat()))
                .toArray(Coordinate[]::new);
            LineString candidate = geometryFactory.createLineString(coordinates);
            if (longest == null || candidate.getLength() > longest.getLength()) {
                longest = candidate;
                longestId = entry.getKey();
            }
        }
        if (longest == null) {
            throw new IllegalStateException("aucun tracé (shape) trouvé pour la route");
        }
        log.info("[GTFS] {} shape(s) candidat(s) ; tracé retenu (le plus long) : {}", pointsByShape.size(), longestId);
        return longest;
    }

    static int toSeconds(String hms) {
        String[] parts = hms.split(":");
        return Integer.parseInt(parts[0]) * 3600 + Integer.parseInt(parts[1]) * 60 + Integer.parseInt(parts[2]);
    }

    private static String safe(CSVRecord record, String column) {
        return safe(record, column, null);
    }

    private static String safe(CSVRecord record, String column, String defaultValue) {
        if (record.isMapped(column) && !record.get(column).isBlank()) {
            return record.get(column);
        }
        return defaultValue;
    }

    private CSVParser openCsv(ZipFile zipFile, String entryName) throws IOException {
        ZipEntry entry = zipFile.getEntry(entryName);
        if (entry == null) {
            throw new IllegalStateException("entrée GTFS manquante dans le zip: " + entryName);
        }
        BufferedReader reader = new BufferedReader(
            new InputStreamReader(zipFile.getInputStream(entry), StandardCharsets.UTF_8));
        return CSV_FORMAT.parse(reader);
    }

    private <T> List<T> saveAllInBatches(JpaRepository<T, ?> repository, List<T> entities) {
        List<T> saved = new ArrayList<>(entities.size());
        for (int i = 0; i < entities.size(); i += BATCH_SIZE) {
            List<T> chunk = entities.subList(i, Math.min(i + BATCH_SIZE, entities.size()));
            saved.addAll(repository.saveAll(chunk));
        }
        return saved;
    }

    private record RouteInfo(String shortName, String color) {
    }

    private record TripRow(String tripId, String headsign, Short direction) {
    }

    private record TripsParseResult(List<TripRow> tripRows, Set<String> shapeIds) {
    }

    private record StopTimeRow(String tripId, String stopId, int stopSequence, int arrivalSec, int departureSec) {
    }

    private record StopTimesParseResult(List<StopTimeRow> rows, Set<String> stopIds) {
    }

    private record ShapePoint(int sequence, double lon, double lat) {
    }
}
