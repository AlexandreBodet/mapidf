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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import com.mapidf.configurations.properties.NetworkProperties;
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
 * Charge un GTFS statique (zip IDFM volumineux : stop_times.txt pèse 909 Mo décompressé pour
 * 10,5 millions de lignes) en ne conservant en mémoire QUE ce qu'exige le périmètre décrit par
 * {@code app.network}. Aucun fichier n'est jamais chargé intégralement en RAM : chaque .txt est
 * parcouru en flux (BufferedReader + Commons CSV en mode itérateur) et filtré au fil de l'eau.
 *
 * <p><b>Pourquoi deux passes sur stop_times.txt.</b> Choisir les parcours à persister demande de
 * connaître leur desserte, et connaître leur desserte demande de lire stop_times.txt. Tout garder
 * pour trancher ensuite ferait 941 959 entités en mémoire sur les 16 lignes de métro — l'OOM que
 * le streaming du zip devait éviter, revenu par la porte de derrière. La passe 1 ne retient donc
 * que des <b>compteurs</b> (37 163 entiers), de quoi élire la course la plus desservante par
 * (route, sens, tracé) ; la passe 2 ne matérialise les lignes que des 112 courses ainsi élues.
 *
 * <p>La propriété obtenue est que le pic mémoire est <b>indépendant de la taille de
 * stop_times.txt</b> (909 Mo, 10,5 millions de lignes) : c'est le fichier qui domine le zip, et
 * il n'est jamais retenu, seulement traversé deux fois. Le pic reste en revanche
 * <b>O(courses du périmètre)</b> via {@code tripRows} et {@code stopCounts} — 37 163 sur le
 * métro, et ce serait de l'ordre de 500 000 si {@code app.network.modes} incluait BUS. Élargir le
 * périmètre au bus demanderait donc de réexaminer ces deux structures.
 *
 * <p>Ne persister que les parcours représentatifs est sans perte fonctionnelle : {@code
 * calendar.txt} n'étant pas chargé, la table est de toute façon incapable de répondre à un
 * horaire théorique daté. Elle sert à connaître l'<b>ordre et l'espacement</b> des arrêts d'une
 * branche, et 915 lignes y suffisent.
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
    private final NetworkProperties network;
    private final GeometryFactory geometryFactory = new GeometryFactory(new PrecisionModel(), SRID);

    @Transactional
    public void load(InputStream zipIn) throws IOException {
        Path tempZip = Files.createTempFile("gtfs-static-", ".zip");
        try {
            Files.copy(zipIn, tempZip, StandardCopyOption.REPLACE_EXISTING);
            loadFromZipFile(tempZip);
        } finally {
            Files.deleteIfExists(tempZip);
        }
    }

    private void loadFromZipFile(Path zipPath) throws IOException {
        try (ZipFile zipFile = new ZipFile(zipPath.toFile())) {
            stopTimeRepository.deleteAllInBatch();
            branchRepository.deleteAllInBatch();
            stopRepository.deleteAllInBatch();
            routeRepository.deleteAllInBatch();

            // 1. Les lignes du périmètre, découvertes par mode.
            Map<String, LineDescriptor> lines = discoverLines(zipFile);
            if (lines.isEmpty()) {
                throw new IllegalStateException("aucune ligne pour les modes " + network.modes());
            }

            // 2. Les courses de ces lignes, indexées par (route, sens, tracé).
            List<TripRow> tripRows = parseTrips(zipFile, lines.keySet());

            // 3. PASSE 1 sur stop_times.txt : UNIQUEMENT le nombre d'arrêts par course.
            //    37 163 compteurs sur le métro réel, mémoire triviale — c'est cette passe qui
            //    remplace l'accumulation de 941 959 entités.
            Map<String, Integer> stopCounts = countStopsPerTrip(zipFile, tripRows);

            // 4. Meilleure course par (route, sens, tracé) : 112 candidates sur le métro réel.
            List<TripRow> candidates = bestTripPerShape(tripRows, stopCounts);

            // 5. PASSE 2 sur stop_times.txt : les lignes des seules 112 candidates. On a
            //    désormais leurs arrêts, donc de quoi faire tourner le glouton.
            Map<String, List<StopTimeRow>> rowsByTrip = parseStopTimesOfTrips(zipFile,
                candidates.stream().map(TripRow::tripId).collect(Collectors.toSet()));

            // 6. Couverture gloutonne par (route, sens) → 37 branches retenues sur le métro.
            List<RetainedBranch> retained = selectBranches(lines, candidates, rowsByTrip);

            // 7. Les tracés des seules branches retenues.
            Map<String, LineString> shapes = loadShapes(zipFile,
                retained.stream().map(RetainedBranch::shapeId).collect(Collectors.toSet()));

            // 8. Les arrêts des branches retenues ET leurs stations parentes.
            Set<String> retainedTripIds = retained.stream()
                .map(RetainedBranch::tripId).collect(Collectors.toSet());
            rowsByTrip.keySet().retainAll(retainedTripIds); // 915 lignes au lieu de 941 959
            Set<String> stopIds = rowsByTrip.values().stream()
                .flatMap(List::stream).map(StopTimeRow::stopId).collect(Collectors.toSet());
            Map<String, Stop> stopsByGtfsId = persistStopsWithParents(zipFile, stopIds);

            persistRoutesBranchesAndStopTimes(lines, retained, shapes, rowsByTrip, stopsByGtfsId);
        }
    }

    /** routes.txt filtré sur app.network.modes, hors exclusions. */
    private Map<String, LineDescriptor> discoverLines(ZipFile zipFile) throws IOException {
        Map<String, LineDescriptor> lines = new LinkedHashMap<>();
        try (CSVParser parser = openCsv(zipFile, "routes.txt")) {
            for (CSVRecord r : parser) {
                String routeId = r.get("route_id");
                if (network.isExcluded(routeId)) {
                    continue;
                }
                Optional<TransportMode> mode = TransportMode
                    .fromRouteType(Integer.parseInt(r.get("route_type")));
                if (mode.isEmpty() || !network.tracks(mode.get())) {
                    continue;
                }
                lines.put(routeId, LineDescriptor.of(
                    routeId, r.get("route_short_name"), safe(r, "route_color"), mode.get()));
            }
        }
        log.info("[GTFS] {} ligne(s) découverte(s) pour les modes {}", lines.size(), network.modes());
        return lines;
    }

    /**
     * Les courses des lignes du périmètre. Une course sans {@code shape_id} est écartée : une
     * branche EST un tracé, il n'y a rien à en faire d'autre.
     * <p>
     * Cet écart est journalisé en WARN, parce qu'il est <b>partiel</b> : le {@code throw} ci-dessous
     * ne se déclenche que si TOUTES les lignes du périmètre sont sans tracé. Une seule ligne de
     * métro sur 16 dont les courses n'auraient pas de {@code shape_id} donnerait sinon une
     * {@code Route} persistée à zéro branche — invisible dans les logs, indiagnosticable sur un
     * feed de 109 Mo maintenu par un tiers. Le décompte par ligne de {@link #selectBranches} est
     * l'autre moitié de ce garde-fou.
     */
    private List<TripRow> parseTrips(ZipFile zipFile, Set<String> routeIds) throws IOException {
        List<TripRow> tripRows = new ArrayList<>();
        int untracedTrips = 0;
        try (CSVParser parser = openCsv(zipFile, "trips.txt")) {
            for (CSVRecord r : parser) {
                String routeId = r.get("route_id");
                if (!routeIds.contains(routeId)) {
                    continue;
                }
                String shapeId = safe(r, "shape_id");
                if (shapeId == null) {
                    untracedTrips++;
                    continue;
                }
                tripRows.add(new TripRow(
                    routeId,
                    r.get("trip_id"),
                    safe(r, "trip_headsign"),
                    Short.parseShort(safe(r, "direction_id", "0")),
                    shapeId));
            }
        }
        if (untracedTrips > 0) {
            log.warn("[GTFS] {} course(s) du périmètre écartée(s) faute de shape_id : sans tracé,"
                + " une branche n'est pas plaçable", untracedTrips);
        }
        if (tripRows.isEmpty()) {
            throw new IllegalStateException("aucune course tracée pour les lignes " + routeIds);
        }
        return tripRows;
    }

    private Map<String, Integer> countStopsPerTrip(ZipFile zipFile, List<TripRow> tripRows) throws IOException {
        Set<String> tripIds = tripRows.stream().map(TripRow::tripId).collect(Collectors.toSet());
        Map<String, Integer> counts = new HashMap<>();
        try (CSVParser parser = openCsv(zipFile, "stop_times.txt")) {
            for (CSVRecord r : parser) {
                String tripId = r.get("trip_id");
                if (tripIds.contains(tripId)) {
                    counts.merge(tripId, 1, Integer::sum);
                }
            }
        }
        return counts;
    }

    /**
     * La course la plus desservante par (route, sens, tracé) : 112 candidates sur le métro réel,
     * contre 37 163 courses au total. C'est sur ce sous-ensemble que la passe 2 travaille.
     */
    private List<TripRow> bestTripPerShape(List<TripRow> tripRows, Map<String, Integer> stopCounts) {
        Map<BranchKey, TripRow> bestByKey = new LinkedHashMap<>();
        for (TripRow row : tripRows) {
            BranchKey key = new BranchKey(row.routeId(), row.direction(), row.shapeId());
            TripRow current = bestByKey.get(key);
            if (current == null
                || stopCounts.getOrDefault(row.tripId(), 0) > stopCounts.getOrDefault(current.tripId(), 0)) {
                bestByKey.put(key, row);
            }
        }
        return List.copyOf(bestByKey.values());
    }

    /**
     * Couverture gloutonne par (route, sens), à partir des arrêts collectés en passe 2 pour les
     * seules candidates. Sur le métro réel : 112 candidates → 37 branches retenues, 100 % des
     * arrêts couverts.
     */
    private List<RetainedBranch> selectBranches(Map<String, LineDescriptor> lines,
                                                List<TripRow> candidates,
                                                Map<String, List<StopTimeRow>> rowsByTrip) {
        Map<DirectionKey, List<TripRow>> byDirection = candidates.stream()
            .collect(Collectors.groupingBy(
                row -> new DirectionKey(row.routeId(), row.direction()),
                LinkedHashMap::new, Collectors.toList()));

        List<RetainedBranch> retained = new ArrayList<>();
        byDirection.forEach((direction, rows) -> {
            Map<String, TripRow> byTripId = rows.stream()
                .collect(Collectors.toMap(TripRow::tripId, row -> row, (a, b) -> a, LinkedHashMap::new));
            List<BranchSelector.Candidate> selectorInput = rows.stream()
                .map(row -> new BranchSelector.Candidate(row.shapeId(), row.tripId(),
                    rowsByTrip.getOrDefault(row.tripId(), List.of()).stream()
                        .map(StopTimeRow::stopId).toList()))
                .toList();
            for (BranchSelector.Candidate kept : BranchSelector.select(selectorInput)) {
                TripRow row = byTripId.get(kept.tripId());
                retained.add(new RetainedBranch(direction.routeId(), direction.direction(),
                    kept.shapeId(), kept.tripId(), row.headsign()));
            }
        });
        logSelectionPerLine(lines, candidates, retained);
        return retained;
    }

    /**
     * Décompte <b>par ligne</b>, et non agrégé : c'est la seule forme qui rend visible d'un coup
     * d'œil une ligne à zéro branche. On itère sur les lignes découvertes, pas sur les candidates,
     * précisément pour que la ligne qui n'a produit aucune candidate apparaisse quand même.
     */
    private void logSelectionPerLine(Map<String, LineDescriptor> lines, List<TripRow> candidates,
                                     List<RetainedBranch> retained) {
        Map<String, Long> candidatesByRoute = candidates.stream()
            .collect(Collectors.groupingBy(TripRow::routeId, Collectors.counting()));
        Map<String, Long> branchesByRoute = retained.stream()
            .collect(Collectors.groupingBy(RetainedBranch::routeId, Collectors.counting()));
        lines.forEach((routeId, descriptor) -> {
            long branchCount = branchesByRoute.getOrDefault(routeId, 0L);
            if (branchCount == 0) {
                log.warn("[GTFS] ligne {} ({}) : AUCUNE branche retenue, elle n'apparaîtra pas"
                    + " sur la carte", descriptor.shortName(), routeId);
            } else {
                log.info("[GTFS] ligne {} ({}) : {} candidate(s) → {} branche(s) retenue(s)",
                    descriptor.shortName(), routeId, candidatesByRoute.getOrDefault(routeId, 0L),
                    branchCount);
            }
        });
    }

    /** PASSE 2 : les lignes des seules courses données, groupées et triées par stop_sequence. */
    private Map<String, List<StopTimeRow>> parseStopTimesOfTrips(ZipFile zipFile, Set<String> tripIds)
            throws IOException {
        Map<String, List<StopTimeRow>> byTrip = new LinkedHashMap<>();
        try (CSVParser parser = openCsv(zipFile, "stop_times.txt")) {
            for (CSVRecord r : parser) {
                String tripId = r.get("trip_id");
                if (!tripIds.contains(tripId)) {
                    continue;
                }
                byTrip.computeIfAbsent(tripId, key -> new ArrayList<>()).add(new StopTimeRow(
                    tripId, r.get("stop_id"), Integer.parseInt(r.get("stop_sequence")),
                    toSeconds(r.get("arrival_time")), toSeconds(r.get("departure_time"))));
            }
        }
        byTrip.values().forEach(rows -> rows.sort(Comparator.comparingInt(StopTimeRow::stopSequence)));
        return byTrip;
    }

    /** Les tracés des seules branches retenues (37 sur le métro, 8 110 points au total). */
    private Map<String, LineString> loadShapes(ZipFile zipFile, Set<String> shapeIds) throws IOException {
        Map<String, List<ShapePoint>> pointsByShape = new LinkedHashMap<>();
        try (CSVParser parser = openCsv(zipFile, "shapes.txt")) {
            for (CSVRecord r : parser) {
                String shapeId = r.get("shape_id");
                if (!shapeIds.contains(shapeId)) {
                    continue;
                }
                pointsByShape.computeIfAbsent(shapeId, key -> new ArrayList<>()).add(new ShapePoint(
                    Integer.parseInt(r.get("shape_pt_sequence")),
                    Double.parseDouble(r.get("shape_pt_lon")),
                    Double.parseDouble(r.get("shape_pt_lat"))));
            }
        }
        Map<String, LineString> shapes = new LinkedHashMap<>();
        pointsByShape.forEach((shapeId, points) -> {
            points.sort(Comparator.comparingInt(ShapePoint::sequence));
            shapes.put(shapeId, geometryFactory.createLineString(points.stream()
                .map(p -> new Coordinate(p.lon(), p.lat()))
                .toArray(Coordinate[]::new)));
        });
        if (shapes.size() < shapeIds.size()) {
            throw new IllegalStateException("tracé manquant pour " + (shapeIds.size() - shapes.size()) + " branche(s)");
        }
        return shapes;
    }

    private void persistRoutesBranchesAndStopTimes(Map<String, LineDescriptor> lines,
                                                    List<RetainedBranch> retained,
                                                    Map<String, LineString> shapes,
                                                    Map<String, List<StopTimeRow>> rowsByTrip,
                                                    Map<String, Stop> stopsByGtfsId) {
        Map<String, Route> routesByGtfsId = new LinkedHashMap<>();
        for (LineDescriptor descriptor : lines.values()) {
            routesByGtfsId.put(descriptor.gtfsRouteId(), routeRepository.save(Route.builder()
                .gtfsId(descriptor.gtfsRouteId())
                .shortName(descriptor.shortName())
                .color(descriptor.color())
                .mode(descriptor.mode().name())
                .siriLineRef(descriptor.siriLineRef())
                .build()));
        }

        List<StopTime> stopTimesToSave = new ArrayList<>();
        for (RetainedBranch item : retained) {
            List<StopTimeRow> rows = rowsByTrip.getOrDefault(item.tripId(), List.of());
            // Terminus = dernier arrêt du parcours, PAS le trip_headsign : c'est lui qui départage
            // deux branches d'un même sens face au DestinationName du flux temps réel, lequel
            // nomme un arrêt. Le headsign n'est qu'un repli quand la desserte est vide.
            String terminus = rows.isEmpty() ? item.headsign()
                : requireStop(stopsByGtfsId, rows.getLast().stopId()).getName();
            Branch branch = branchRepository.save(Branch.builder()
                .route(routesByGtfsId.get(item.routeId()))
                .gtfsShapeId(item.shapeId())
                .representativeTrip(item.tripId())
                .direction(item.direction())
                .terminusName(terminus)
                .geom(shapes.get(item.shapeId()))
                .build());
            for (StopTimeRow row : rows) {
                stopTimesToSave.add(StopTime.builder()
                    .branch(branch)
                    .stop(requireStop(stopsByGtfsId, row.stopId()))
                    .stopSequence(row.stopSequence())
                    .arrivalSec(row.arrivalSec())
                    .departureSec(row.departureSec())
                    .build());
            }
        }
        saveAllInBatches(stopTimeRepository, stopTimesToSave);
        log.info("[GTFS] {} route(s), {} branche(s), {} stop_time(s) persistés",
            routesByGtfsId.size(), retained.size(), stopTimesToSave.size());
    }

    /**
     * Un {@code stop_id} de stop_times.txt absent de stops.txt est une incohérence du feed. Sans
     * ce contrôle, le {@code null} se propagerait en NPE au déréférencement du nom, ou en violation
     * de contrainte NOT NULL à l'insertion — dans les deux cas sans nommer le coupable.
     */
    private static Stop requireStop(Map<String, Stop> stopsByGtfsId, String stopId) {
        Stop stop = stopsByGtfsId.get(stopId);
        if (stop == null) {
            throw new IllegalStateException(
                "stop_times.txt référence un stop_id absent de stops.txt: " + stopId);
        }
        return stop;
    }

    /**
     * Les quais des branches retenues ET leurs stations parentes, en deux lectures de stops.txt.
     * Les parents deviennent des arrêts à part entière : ils portent leur propre nom et leurs
     * propres coordonnées, ce qui rend le nom d'une station de correspondance déterministe (il
     * venait sinon du premier quai rencontré).
     * <p>
     * La seconde lecture retient un parent sur son seul {@code stop_id}, <b>sans</b> vérifier
     * {@code location_type=1} : les identifiants viennent de {@code parent_station}, il n'y a donc
     * aucune ambiguïté à lever, et un tel filtre ne pourrait que faire disparaître silencieusement
     * un parent que des quais référencent — les quais garderaient alors un
     * {@code parent_station} pendant vers un arrêt inexistant. La colonne est en outre absente de
     * certains feeds (et de quatre de nos fixtures — {@code gtfs-mini}, {@code gtfs-multi},
     * {@code gtfs-twoshapes} et {@code gtfs-parent} ; seule {@code gtfs-branch} la porte), où
     * filtrer dessus supprimerait tous les parents.
     */
    private Map<String, Stop> persistStopsWithParents(ZipFile zipFile, Set<String> stopIds) throws IOException {
        // Mesuré sur le métro : les 781 quais ont TOUS un parent, présent dans stops.txt.
        // On mappe dès la lecture plutôt que de retenir les CSVRecord : chacun garde une
        // référence forte vers son parser, et leur lisibilité après close() n'est pas contractuelle.
        List<StopRow> all = new ArrayList<>();
        Set<String> parentIds = new HashSet<>();
        try (CSVParser parser = openCsv(zipFile, "stops.txt")) {
            for (CSVRecord r : parser) {
                if (stopIds.contains(r.get("stop_id"))) {
                    StopRow row = toStopRow(r);
                    all.add(row);
                    if (row.parentStation() != null) {
                        parentIds.add(row.parentStation());
                    }
                }
            }
        }
        try (CSVParser parser = openCsv(zipFile, "stops.txt")) {
            for (CSVRecord r : parser) {
                if (parentIds.contains(r.get("stop_id"))) {
                    all.add(toStopRow(r));
                }
            }
        }
        List<Stop> toSave = all.stream()
            .map(row -> Stop.builder()
                .gtfsId(row.gtfsId())
                .name(row.name())
                .parentStation(row.parentStation())
                .geom(geometryFactory.createPoint(new Coordinate(row.lon(), row.lat())))
                .build())
            .toList();
        Map<String, Stop> byGtfsId = new HashMap<>();
        for (Stop stop : saveAllInBatches(stopRepository, toSave)) {
            byGtfsId.put(stop.getGtfsId(), stop);
        }
        return byGtfsId;
    }

    private static StopRow toStopRow(CSVRecord r) {
        return new StopRow(
            r.get("stop_id"),
            r.get("stop_name"),
            safe(r, "parent_station"),
            Double.parseDouble(r.get("stop_lon")),
            Double.parseDouble(r.get("stop_lat")));
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

    private record TripRow(String routeId, String tripId, String headsign, Short direction, String shapeId) {
    }

    private record BranchKey(String routeId, Short direction, String shapeId) {
    }

    private record DirectionKey(String routeId, Short direction) {
    }

    private record RetainedBranch(String routeId, Short direction, String shapeId,
                                  String tripId, String headsign) {
    }

    private record StopTimeRow(String tripId, String stopId, int stopSequence, int arrivalSec, int departureSec) {
    }

    private record StopRow(String gtfsId, String name, String parentStation, double lon, double lat) {
    }

    private record ShapePoint(int sequence, double lon, double lat) {
    }
}
