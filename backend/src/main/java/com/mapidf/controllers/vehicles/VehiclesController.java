package com.mapidf.controllers.vehicles;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.mapidf.controllers.vehicles.VehiclesResponse.VehicleDto;
import com.mapidf.network.LineRegistry;
import com.mapidf.network.TrackedLine;
import com.mapidf.position.PositionEngine;
import com.mapidf.position.Vehicle;
import com.mapidf.rt.RealtimePoller;
import com.mapidf.rt.RtSnapshot;
import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tous les véhicules du réseau suivi en un appel : le front fait UN poll toutes les 4 s, pas
 * seize. Aucune requête SQL — registry en mémoire et snapshot temps réel.
 *
 * <p>Les positions dépendent de l'instant, donc elles sont recalculées à chaque requête ; ce qui
 * est immuable (géométries indexées, arrêts projetés) est préconstruit dans le registry.
 */
@RestController
@AllArgsConstructor
public class VehiclesController {

    private final LineRegistry registry;
    private final PositionEngine positionEngine;
    private final RealtimePoller poller;

    @GetMapping("/vehicles")
    public VehiclesResponse vehicles() {
        Instant now = Instant.now();
        RtSnapshot snapshot = poller.current();
        List<VehicleDto> vehicles = new ArrayList<>();
        for (TrackedLine line : registry.current().lines()) {
            for (Vehicle vehicle : positionEngine.computeAll(
                    line, snapshot.forLine(line.siriLineRef()), now)) {
                vehicles.add(VehicleDto.from(vehicle));
            }
        }
        return new VehiclesResponse(now, vehicles);
    }
}
