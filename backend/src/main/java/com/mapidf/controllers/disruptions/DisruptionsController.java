package com.mapidf.controllers.disruptions;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.mapidf.controllers.disruptions.DisruptionsResponse.Item;
import com.mapidf.controllers.disruptions.DisruptionsResponse.LineDisruptions;
import com.mapidf.controllers.disruptions.DisruptionsResponse.StationDisruption;
import com.mapidf.disruptions.Disruption;
import com.mapidf.disruptions.DisruptionPoller;
import com.mapidf.disruptions.DisruptionSnapshot;
import com.mapidf.network.LineRegistry;
import com.mapidf.network.NetworkSnapshot;
import com.mapidf.network.Station;
import com.mapidf.network.TrackedLine;
import com.mapidf.position.PositionEngine;
import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Perturbations en cours du réseau suivi. Aucun appel PRIM ni SQL : l'instantané est en mémoire,
 * et le filtre « en cours » est appliqué à l'instant de la requête.
 */
@RestController
@AllArgsConstructor
public class DisruptionsController {

    private final LineRegistry registry;
    private final DisruptionPoller poller;

    @GetMapping("/disruptions")
    public DisruptionsResponse disruptions() {
        Instant now = Instant.now();
        DisruptionSnapshot snapshot = poller.current();
        NetworkSnapshot network = registry.current();

        List<LineDisruptions> lines = new ArrayList<>();
        for (TrackedLine line : network.lines()) {
            List<Disruption> active = snapshot.forLine(line.id(), now);
            if (active.isEmpty()) {
                continue;
            }
            lines.add(new LineDisruptions(line.id(),
                // La liste est déjà triée par gravité décroissante par le snapshot.
                active.getFirst().severity().name(),
                active.stream().map(DisruptionsController::toItem).toList()));
        }
        return new DisruptionsResponse(now, lines, disruptedStations(snapshot, network, now));
    }

    /**
     * Quais perturbés → stations parentes. Une correspondance peut cumuler plusieurs quais
     * touchés : on ne garde que la pire gravité, puisque la carte n'a qu'un anneau à dessiner.
     */
    private static List<StationDisruption> disruptedStations(DisruptionSnapshot snapshot,
                                                             NetworkSnapshot network, Instant now) {
        Map<String, String> stationIdByStopKey = new HashMap<>();
        for (Station station : network.stations()) {
            for (String platformId : station.platformIds()) {
                stationIdByStopKey.put(PositionEngine.stopKey(platformId), station.id());
            }
        }
        Map<String, Disruption.Severity> worstByStation = new LinkedHashMap<>();
        for (String stopKey : snapshot.byStop().keySet()) {
            List<Disruption> active = snapshot.forStop(stopKey, now);
            String stationId = stationIdByStopKey.get(stopKey);
            if (active.isEmpty() || stationId == null) {
                continue;
            }
            // Trié pire d'abord par le snapshot, et compareTo suit l'ordre de l'enum.
            worstByStation.merge(stationId, active.getFirst().severity(),
                (a, b) -> a.compareTo(b) <= 0 ? a : b);
        }
        return worstByStation.entrySet().stream()
            .map(entry -> new StationDisruption(entry.getKey(), entry.getValue().name()))
            .toList();
    }

    private static Item toItem(Disruption disruption) {
        return new Item(disruption.severity().name(), disruption.cause(),
            disruption.title(), disruption.shortMessage());
    }
}
