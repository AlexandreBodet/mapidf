package com.mapidf.controllers.disruptions;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.mapidf.controllers.disruptions.DisruptionsResponse.Item;
import com.mapidf.controllers.disruptions.DisruptionsResponse.LineDisruptions;
import com.mapidf.disruptions.Disruption;
import com.mapidf.disruptions.DisruptionPoller;
import com.mapidf.network.LineRegistry;
import com.mapidf.network.TrackedLine;
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
        var snapshot = poller.current();
        List<LineDisruptions> lines = new ArrayList<>();
        for (TrackedLine line : registry.current().lines()) {
            List<Disruption> active = snapshot.forLine(line.id(), now);
            if (active.isEmpty()) {
                continue;
            }
            lines.add(new LineDisruptions(line.id(),
                // La liste est déjà triée par gravité décroissante par le snapshot.
                active.getFirst().severity().name(),
                active.stream().map(DisruptionsController::toItem).toList()));
        }
        return new DisruptionsResponse(now, lines);
    }

    private static Item toItem(Disruption disruption) {
        return new Item(disruption.severity().name(), disruption.cause(),
            disruption.title(), disruption.shortMessage());
    }
}
