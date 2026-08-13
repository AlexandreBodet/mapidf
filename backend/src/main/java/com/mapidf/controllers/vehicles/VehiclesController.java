package com.mapidf.controllers.vehicles;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.mapidf.controllers.support.ResponseCache;
import com.mapidf.controllers.vehicles.VehiclesResponse.VehicleDto;
import com.mapidf.network.LineRegistry;
import com.mapidf.network.NetworkSnapshot;
import com.mapidf.network.TrackedLine;
import com.mapidf.position.PositionEngine;
import com.mapidf.position.Vehicle;
import com.mapidf.rt.RealtimePoller;
import com.mapidf.rt.RtSnapshot;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

/**
 * Tous les véhicules du réseau suivi en un appel : le front fait UN poll toutes les 4 s, pas
 * seize. Aucune requête SQL — registry en mémoire et snapshot temps réel.
 *
 * <p>Les positions dépendent de l'instant, donc elles sont recalculées ; mais au plus une fois
 * par seconde, la réponse étant mémorisée SÉRIALISÉE. C'est le seul des trois endpoints à cacher
 * des octets : ne mémoriser que l'objet laisserait la sérialisation de ~705 véhicules se payer
 * une fois par REQUÊTE, quand le calcul se paie une fois par seconde — ce terme-là finit donc
 * par dominer quand le débit monte, si léger soit-il par appel.
 */
@RestController
public class VehiclesController {

    private final LineRegistry registry;
    private final PositionEngine positionEngine;
    private final RealtimePoller poller;
    private final ObjectMapper json;
    private final ResponseCache<Void, byte[]> cache;

    public VehiclesController(LineRegistry registry, PositionEngine positionEngine,
                              RealtimePoller poller, ObjectMapper json,
                              Clock clock, MeterRegistry meters) {
        this.registry = registry;
        this.positionEngine = positionEngine;
        this.poller = poller;
        this.json = json;
        this.cache = new ResponseCache<>(clock, "vehicles", meters);
    }

    @GetMapping("/vehicles")
    public ResponseEntity<byte[]> vehicles() {
        // Les deux instantanés sont lus UNE fois, et servent à la fois de clé et d'entrée au
        // calcul. Les relire dans le lambda ouvrirait une fenêtre : un poll survenu entre-temps
        // ferait enregistrer une réponse fraîche sous l'identité de l'ancien instantané, donc
        // périmée jusqu'au poll suivant et non jusqu'à la seconde suivante.
        RtSnapshot snapshot = poller.current();
        NetworkSnapshot network = registry.current();

        byte[] body = cache.get(List.of(snapshot, network),
            now -> json.writeValueAsBytes(build(snapshot, network, now)));

        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_JSON)
            .cacheControl(CacheControl.noStore())
            .body(body);
    }

    private VehiclesResponse build(RtSnapshot snapshot, NetworkSnapshot network, Instant now) {
        List<VehicleDto> vehicles = new ArrayList<>();
        for (TrackedLine line : network.lines()) {
            for (Vehicle vehicle : positionEngine.computeAll(
                    line, snapshot.forLine(line.siriLineRef()), now)) {
                vehicles.add(VehicleDto.from(vehicle));
            }
        }
        return new VehiclesResponse(snapshot.dataDate(), poller.inServiceNow(), vehicles);
    }
}
