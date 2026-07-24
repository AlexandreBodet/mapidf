package com.mapidf.services;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.mapidf.controllers.lines.DeparturesResponse;
import com.mapidf.controllers.lines.DeparturesResponse.Direction;
import com.mapidf.controllers.lines.DeparturesResponse.Passage;
import com.mapidf.position.PositionEngine;
import com.mapidf.rt.RtSnapshot.LiveJourney;
import org.springframework.stereotype.Service;

/**
 * Prochains passages à une station, agrégés depuis le snapshot temps réel déjà en mémoire
 * (aucun appel PRIM). Un passage = un {@code Call} futur d'une course, dont l'arrêt appartient
 * à la station (match par {@link PositionEngine#stopKey}). Regroupé par destination.
 */
@Service
public class StationDepartureService {

    public DeparturesResponse departures(String stationName, Set<String> stopKeys,
                                         List<LiveJourney> journeys, Instant now, int perDirection) {
        // destination -> passages futurs à cette station, dans l'ordre d'insertion des destinations
        Map<String, List<Passage>> byDestination = new LinkedHashMap<>();
        for (LiveJourney journey : journeys) {
            for (LiveJourney.Call call : journey.calls()) {
                if (call.time() == null || call.time().isBefore(now)) {
                    continue;
                }
                if (!stopKeys.contains(PositionEngine.stopKey(call.stopRef()))) {
                    continue;
                }
                byDestination.computeIfAbsent(journey.destination(), k -> new ArrayList<>())
                    .add(new Passage(call.time(), call.departureStatus()));
            }
        }

        List<Direction> directions = new ArrayList<>();
        byDestination.forEach((destination, passages) -> {
            List<Passage> sorted = passages.stream()
                .sorted(Comparator.comparing(Passage::expectedTime))
                .limit(perDirection)
                .toList();
            directions.add(new Direction(destination, sorted));
        });
        return new DeparturesResponse(stationName, directions);
    }
}
