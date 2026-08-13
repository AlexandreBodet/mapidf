package com.mapidf.controllers.stations;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.mapidf.controllers.disruptions.DisruptionsResponse;
import com.mapidf.controllers.support.ResponseCache;
import com.mapidf.disruptions.Disruption;
import com.mapidf.disruptions.DisruptionPoller;
import com.mapidf.disruptions.DisruptionSnapshot;
import com.mapidf.network.LineRegistry;
import com.mapidf.network.NetworkSnapshot;
import com.mapidf.network.Station;
import com.mapidf.network.TrackedLine;
import com.mapidf.position.PositionEngine;
import com.mapidf.rt.RealtimePoller;
import com.mapidf.rt.RtSnapshot;
import com.mapidf.services.StationDepartureService;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class StationsController {

    private static final int PASSAGES_PER_DIRECTION = 3;

    private final LineRegistry registry;
    private final RealtimePoller poller;
    private final DisruptionPoller disruptionPoller;
    private final StationDepartureService departureService;
    private final ResponseCache<String, DeparturesResponse> cache;

    public StationsController(LineRegistry registry, RealtimePoller poller,
                              DisruptionPoller disruptionPoller,
                              StationDepartureService departureService,
                              Clock clock, MeterRegistry meters) {
        this.registry = registry;
        this.poller = poller;
        this.disruptionPoller = disruptionPoller;
        this.departureService = departureService;
        this.cache = new ResponseCache<>(clock, "departures", meters);
    }

    @GetMapping("/stations/{id}/departures")
    public ResponseEntity<DeparturesResponse> departures(@PathVariable String id) {
        // requireStation AVANT le cache : un identifiant inconnu lève, donc la map reste bornée
        // par le nombre de stations du registry — pas d'éviction à écrire, pas de saturation par
        // identifiant forgé.
        Station station = registry.requireStation(id);

        RtSnapshot rt = poller.current();
        DisruptionSnapshot disruptions = disruptionPoller.current();
        NetworkSnapshot network = registry.current();

        DeparturesResponse body = cache.get(id, List.of(rt, disruptions, network),
            now -> build(station, rt, disruptions, network, now));

        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_JSON)
            .cacheControl(CacheControl.noStore())
            .body(body);
    }

    private DeparturesResponse build(Station station, RtSnapshot rt,
                                     DisruptionSnapshot disruptionSnapshot,
                                     NetworkSnapshot network, Instant now) {
        // Seules les lignes qui desservent cette station : jusqu'à 5 sur une correspondance.
        List<TrackedLine> lines = station.lineIds().stream()
            .map(lineId -> network.linesById().get(lineId))
            .filter(Objects::nonNull)
            .toList();
        DeparturesResponse departures = departureService.departures(
            station, lines, rt, now, PASSAGES_PER_DIRECTION);
        return new DeparturesResponse(departures.stationName(), departures.lines(),
            disruptionsOf(station, disruptionSnapshot, now));
    }

    /**
     * Perturbations en cours des quais de la station, dédoublonnées : une même perturbation vise
     * souvent plusieurs quais du même nom de station.
     */
    private List<DisruptionsResponse.Item> disruptionsOf(Station station,
                                                         DisruptionSnapshot snapshot, Instant now) {
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
