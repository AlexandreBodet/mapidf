package com.mapidf.services;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.mapidf.controllers.stations.DeparturesResponse;
import com.mapidf.controllers.stations.DeparturesResponse.Direction;
import com.mapidf.controllers.stations.DeparturesResponse.LineDepartures;
import com.mapidf.controllers.stations.DeparturesResponse.Passage;
import com.mapidf.network.Station;
import com.mapidf.network.TrackedLine;
import com.mapidf.position.PositionEngine;
import com.mapidf.rt.RtSnapshot;
import com.mapidf.rt.RtSnapshot.LiveJourney;
import org.springframework.stereotype.Service;

/**
 * Prochains passages à une station, agrégés depuis le snapshot temps réel déjà en mémoire
 * (aucun appel PRIM, aucune requête SQL). Un passage = un appel futur d'une course dont l'arrêt
 * appartient à la station.
 */
@Service
public class StationDepartureService {

    private static final Pattern LEADING_DIGITS = Pattern.compile("^(\\d+)");

    public DeparturesResponse departures(Station station, List<TrackedLine> lines,
                                         RtSnapshot snapshot, Instant now, int perDirection) {
        Set<String> stopKeys = station.platformIds().stream()
            .map(PositionEngine::stopKey)
            .collect(Collectors.toSet());

        List<LineDepartures> byLine = new ArrayList<>();
        for (TrackedLine line : sortedByHumanOrder(lines)) {
            List<Direction> directions = directionsOf(
                snapshot.forLine(line.siriLineRef()), stopKeys, now, perDirection);
            if (!directions.isEmpty()) {
                byLine.add(new LineDepartures(line.id(), line.shortName(), line.color(), directions));
            }
        }
        return new DeparturesResponse(station.name(), byLine);
    }

    private List<Direction> directionsOf(List<LiveJourney> journeys, Set<String> stopKeys,
                                         Instant now, int perDirection) {
        Map<String, List<Passage>> byDestination = new LinkedHashMap<>();
        for (LiveJourney journey : journeys) {
            for (LiveJourney.Call call : journey.calls()) {
                if (call.time() == null || call.time().isBefore(now)) {
                    continue;
                }
                if (!stopKeys.contains(PositionEngine.stopKey(call.stopRef()))) {
                    continue;
                }
                byDestination.computeIfAbsent(journey.destination(), key -> new ArrayList<>())
                    .add(new Passage(journey.journeyRef(), call.time(), call.departureStatus()));
            }
        }
        // Les directions d'une même ligne sont triées ALPHABÉTIQUEMENT par destination. Ce n'est
        // plus le directionRef SIRI qui les ordonne (il ne survit pas au groupement par
        // destination) : l'ordre est donc stable et reproductible, mais arbitraire du point de vue
        // du voyageur. Pinné par StationDepartureServiceTest.keepsBothDirectionsOfTheSameLine.
        return byDestination.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .map(entry -> new Direction(entry.getKey(), entry.getValue().stream()
                .sorted(Comparator.comparing(Passage::expectedTime))
                .limit(perDirection)
                .toList()))
            .toList();
    }

    /**
     * Ordre humain : 3, 3b, 7, 9, 14 — pas l'ordre alphabétique qui donnerait 14 avant 3.
     * Stable entre deux rafraîchissements : trier par passage le plus imminent réordonnerait
     * le panneau sous le curseur toutes les 4 s.
     */
    private static List<TrackedLine> sortedByHumanOrder(List<TrackedLine> lines) {
        return lines.stream()
            .sorted(Comparator.comparingInt(StationDepartureService::leadingNumber)
                .thenComparing(TrackedLine::id))
            .toList();
    }

    private static int leadingNumber(TrackedLine line) {
        Matcher matcher = LEADING_DIGITS.matcher(line.id());
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : Integer.MAX_VALUE;
    }
}
