package com.mapidf.controllers.stations;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.mapidf.controllers.disruptions.DisruptionsResponse;
import com.mapidf.disruptions.Disruption;
import com.mapidf.disruptions.DisruptionPoller;
import com.mapidf.network.LineRegistry;
import com.mapidf.network.Station;
import com.mapidf.network.TrackedLine;
import com.mapidf.position.PositionEngine;
import com.mapidf.rt.RealtimePoller;
import com.mapidf.services.StationDepartureService;
import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
@AllArgsConstructor
public class StationsController {

    private static final int PASSAGES_PER_DIRECTION = 3;

    private final LineRegistry registry;
    private final RealtimePoller poller;
    private final DisruptionPoller disruptionPoller;
    private final StationDepartureService departureService;

    @GetMapping("/stations/{id}/departures")
    public DeparturesResponse departures(@PathVariable String id) {
        Station station = registry.requireStation(id);
        // Seules les lignes qui desservent cette station : jusqu'à 5 sur une correspondance.
        List<TrackedLine> lines = station.lineIds().stream()
            .map(lineId -> registry.current().linesById().get(lineId))
            .filter(Objects::nonNull)
            .toList();
        Instant now = Instant.now();
        DeparturesResponse departures = departureService.departures(
            station, lines, poller.current(), now, PASSAGES_PER_DIRECTION);
        return new DeparturesResponse(departures.stationName(), departures.lines(),
            disruptionsOf(station, now));
    }

    /**
     * Perturbations en cours des quais de la station, dédoublonnées : une même perturbation vise
     * souvent plusieurs quais du même nom de station.
     */
    private List<DisruptionsResponse.Item> disruptionsOf(Station station, Instant now) {
        var snapshot = disruptionPoller.current();
        Map<String, Disruption> byId = new LinkedHashMap<>();
        for (String platformId : station.platformIds()) {
            for (Disruption disruption : snapshot.forStop(PositionEngine.stopKey(platformId), now)) {
                byId.putIfAbsent(disruption.id(), disruption);
            }
        }
        return byId.values().stream()
            .map(disruption -> new DisruptionsResponse.Item(disruption.severity().name(),
                disruption.cause(), disruption.title(), disruption.shortMessage(),
                disruption.detail()))
            .toList();
    }
}
