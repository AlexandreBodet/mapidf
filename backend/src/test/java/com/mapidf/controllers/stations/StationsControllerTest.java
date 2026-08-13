package com.mapidf.controllers.stations;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import com.mapidf.configurations.properties.PrimProperties;
import com.mapidf.disruptions.DisruptionPoller;
import com.mapidf.network.LineRegistry;
import com.mapidf.network.NetworkSnapshot;
import com.mapidf.network.Station;
import com.mapidf.rt.RealtimePoller;
import com.mapidf.services.StationDepartureService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.assertThat;

class StationsControllerTest {

    private static final PrimProperties PRIM = new PrimProperties("", "apikey", "", "",
        Duration.ofSeconds(60), "", Duration.ofSeconds(60));

    /** Horloge figée : la requête n°2 doit tomber dans la MÊME seconde que la n°1, sinon le cache
     *  recalcule pour une raison sans rapport et le test ne discrimine plus rien. */
    private static final Clock FROZEN =
        Clock.fixed(Instant.parse("2026-08-13T08:00:00.400Z"), ZoneOffset.UTC);

    private static NetworkSnapshot networkNaming(String stationName) {
        return NetworkSnapshot.of(List.of(),
            List.of(new Station("ST1", stationName, 48.87, 2.34, List.of("1"), List.of("9"))));
    }

    /** Republie au premier appel : caricature d'un refresh GTFS glissé entre deux lectures. */
    private static final class RepublishingRegistry extends LineRegistry {
        private NetworkSnapshot next;
        private final NetworkSnapshot then;

        RepublishingRegistry(NetworkSnapshot first, NetworkSnapshot then) {
            this.next = first;
            this.then = then;
        }

        @Override
        public NetworkSnapshot current() {
            NetworkSnapshot served = next;
            next = then;
            return served;
        }
    }

    @Test
    void resolvesTheStationOnTheSnapshotThatKeysTheEntry() {
        // Deux lectures du registry par requête, et le corps est bâti sur la station de N1 mais
        // enregistré sous l'identité de N2 : il est alors servi jusqu'à la seconde suivante à des
        // requêtes qui, elles, ne voient que N2. Une seule lecture ferme la fenêtre.
        RepublishingRegistry registry = new RepublishingRegistry(
            networkNaming("Ancienne"), networkNaming("Nouvelle"));
        ObjectMapper json = new ObjectMapper();
        StationsController controller = new StationsController(registry,
            new RealtimePoller(PRIM, json, registry),
            new DisruptionPoller(PRIM, json, registry),
            new StationDepartureService(), FROZEN, new SimpleMeterRegistry());

        controller.departures("ST1");
        DeparturesResponse second = controller.departures("ST1").getBody();

        assertThat(second).isNotNull();
        assertThat(second.stationName()).isEqualTo("Nouvelle");
    }
}
