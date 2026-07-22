package com.mapidf.gtfs;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipInputStream;

import com.mapidf.data.entity.Route;
import com.mapidf.data.entity.Stop;
import com.mapidf.data.entity.StopTime;
import com.mapidf.data.entity.Trip;
import com.mapidf.data.repositories.RouteRepository;
import com.mapidf.data.repositories.StopRepository;
import com.mapidf.data.repositories.StopTimeRepository;
import com.mapidf.data.repositories.TripRepository;
import lombok.AllArgsConstructor;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.PrecisionModel;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@AllArgsConstructor
public class GtfsStaticLoader {

    private static final int SRID = 4326;

    private final RouteRepository routeRepository;
    private final StopRepository stopRepository;
    private final TripRepository tripRepository;
    private final StopTimeRepository stopTimeRepository;
    private final GeometryFactory geometryFactory = new GeometryFactory(new PrecisionModel(), SRID);

    @Transactional
    public void loadFromZip(InputStream zipIn, String routeId) throws IOException {
        Map<String, List<CSVRecord>> files = readZip(zipIn);

        stopTimeRepository.deleteAllInBatch();
        tripRepository.deleteAllInBatch();
        stopRepository.deleteAllInBatch();
        routeRepository.deleteAllInBatch();

        CSVRecord routeRecord = files.get("routes.txt").stream()
            .filter(r -> r.get("route_id").equals(routeId))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("route absente: " + routeId));

        List<CSVRecord> tripRecords = files.get("trips.txt").stream()
            .filter(r -> r.get("route_id").equals(routeId))
            .toList();
        String shapeId = tripRecords.getFirst().get("shape_id");

        Route route = routeRepository.save(Route.builder()
            .gtfsId(routeId)
            .shortName(routeRecord.get("route_short_name"))
            .color(safe(routeRecord, "route_color"))
            .geom(buildShape(files.get("shapes.txt"), shapeId))
            .build());

        Map<String, Trip> tripsByGtfsId = new HashMap<>();
        for (CSVRecord t : tripRecords) {
            Trip trip = tripRepository.save(Trip.builder()
                .gtfsId(t.get("trip_id"))
                .route(route)
                .headsign(safe(t, "trip_headsign"))
                .direction(Short.parseShort(safe(t, "direction_id", "0")))
                .build());
            tripsByGtfsId.put(trip.getGtfsId(), trip);
        }

        List<CSVRecord> stopTimeRecords = files.get("stop_times.txt").stream()
            .filter(r -> tripsByGtfsId.containsKey(r.get("trip_id")))
            .toList();

        Map<String, Stop> stopsByGtfsId = new HashMap<>();
        for (CSVRecord s : files.get("stops.txt")) {
            String stopId = s.get("stop_id");
            boolean referenced = stopTimeRecords.stream().anyMatch(r -> r.get("stop_id").equals(stopId));
            if (!referenced) {
                continue;
            }
            Stop stop = stopRepository.save(Stop.builder()
                .gtfsId(stopId)
                .name(s.get("stop_name"))
                .geom(geometryFactory.createPoint(new Coordinate(
                    Double.parseDouble(s.get("stop_lon")), Double.parseDouble(s.get("stop_lat")))))
                .build());
            stopsByGtfsId.put(stopId, stop);
        }

        for (CSVRecord r : stopTimeRecords) {
            stopTimeRepository.save(StopTime.builder()
                .trip(tripsByGtfsId.get(r.get("trip_id")))
                .stop(stopsByGtfsId.get(r.get("stop_id")))
                .stopSequence(Integer.parseInt(r.get("stop_sequence")))
                .arrivalSec(toSeconds(r.get("arrival_time")))
                .departureSec(toSeconds(r.get("departure_time")))
                .build());
        }
    }

    private LineString buildShape(List<CSVRecord> shapes, String shapeId) {
        List<CSVRecord> points = new ArrayList<>(shapes.stream()
            .filter(r -> r.get("shape_id").equals(shapeId))
            .toList());
        points.sort(Comparator.comparingInt(r -> Integer.parseInt(r.get("shape_pt_sequence"))));
        Coordinate[] coordinates = points.stream()
            .map(r -> new Coordinate(
                Double.parseDouble(r.get("shape_pt_lon")), Double.parseDouble(r.get("shape_pt_lat"))))
            .toArray(Coordinate[]::new);
        return geometryFactory.createLineString(coordinates);
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

    private Map<String, List<CSVRecord>> readZip(InputStream zipIn) throws IOException {
        Map<String, List<CSVRecord>> out = new HashMap<>();
        try (ZipInputStream zis = new ZipInputStream(zipIn)) {
            var entry = zis.getNextEntry();
            while (entry != null) {
                if (entry.getName().endsWith(".txt")) {
                    byte[] bytes = zis.readAllBytes();
                    try (var reader = new InputStreamReader(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8)) {
                        var parser = CSVFormat.DEFAULT.builder()
                            .setHeader().setSkipHeaderRecord(true).setTrim(true).build()
                            .parse(reader);
                        out.put(entry.getName(), parser.getRecords());
                    }
                }
                entry = zis.getNextEntry();
            }
        }
        return out;
    }
}
