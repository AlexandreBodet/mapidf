package com.mapidf.controllers.stations;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

import com.mapidf.network.LineRegistry;
import com.mapidf.network.Station;
import com.mapidf.network.TrackedLine;
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
    private final StationDepartureService departureService;

    @GetMapping("/stations/{id}/departures")
    public DeparturesResponse departures(@PathVariable String id) {
        Station station = registry.requireStation(id);
        // Seules les lignes qui desservent cette station : jusqu'à 5 sur une correspondance.
        List<TrackedLine> lines = station.lineIds().stream()
            .map(lineId -> registry.current().linesById().get(lineId))
            .filter(Objects::nonNull)
            .toList();
        return departureService.departures(
            station, lines, poller.current(), Instant.now(), PASSAGES_PER_DIRECTION);
    }
}
