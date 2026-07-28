package com.mapidf.position;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.mapidf.data.entity.StopTime;
import com.mapidf.data.repositories.StopTimeRepository;
import lombok.AllArgsConstructor;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.linearref.LengthIndexedLine;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@AllArgsConstructor
public class ScheduleProvider {

    private final StopTimeRepository stopTimeRepository;
    private final java.util.Map<String, LineSchedule> cache = new java.util.concurrent.ConcurrentHashMap<>();

    @Transactional(readOnly = true)
    public LineSchedule getLineSchedule(LineString line, String gtfsRouteId) {
        LineSchedule cached = cache.get(gtfsRouteId);
        if (cached != null) {
            return cached;
        }
        LineSchedule computed = computeSchedule(line, gtfsRouteId);
        cache.put(gtfsRouteId, computed);
        return computed;
    }

    // Vidé au rechargement du GTFS (cf. GtfsStaticService.refresh) : l'horaire ne change
    // qu'à ce moment-là, inutile de le recalculer depuis la base à chaque poll /vehicles.
    public void invalidate() {
        cache.clear();
    }

    private LineSchedule computeSchedule(LineString line, String gtfsRouteId) {
        LengthIndexedLine indexed = new LengthIndexedLine(line);

        Map<String, List<StopTime>> stopTimesByTrip = new LinkedHashMap<>();
        Map<String, Short> directionByTrip = new HashMap<>();
        for (StopTime stopTime : stopTimeRepository.findScheduleByRouteGtfsId(gtfsRouteId)) {
            String tripId = stopTime.getTrip().getGtfsId();
            stopTimesByTrip.computeIfAbsent(tripId, key -> new ArrayList<>()).add(stopTime);
            directionByTrip.putIfAbsent(tripId, stopTime.getTrip().getDirection());
        }

        // course représentative par sens = celle qui a le plus d'arrêts
        Map<Short, String> representativeByDirection = new HashMap<>();
        stopTimesByTrip.forEach((tripId, stopTimes) -> {
            Short direction = directionByTrip.get(tripId);
            String current = representativeByDirection.get(direction);
            if (current == null || stopTimes.size() > stopTimesByTrip.get(current).size()) {
                representativeByDirection.put(direction, tripId);
            }
        });

        List<DirectionSchedule> directions = new ArrayList<>();
        for (String tripId : representativeByDirection.values()) {
            List<StopOnLine> stops = stopTimesByTrip.get(tripId).stream()
                .map(st -> new StopOnLine(
                    PositionEngine.stopKey(st.getStop().getGtfsId()),
                    st.getStop().getName(),
                    indexed.project(st.getStop().getGeom().getCoordinate()),
                    st.getDepartureSec()))
                .toList();
            String terminus = stops.isEmpty() ? "" : stops.getLast().stopName();
            directions.add(new DirectionSchedule(terminus, stops));
        }
        return new LineSchedule(directions);
    }
}
