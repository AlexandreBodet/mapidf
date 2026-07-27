package com.mapidf.services;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import com.mapidf.controllers.lines.DeparturesResponse;
import com.mapidf.rt.RtSnapshot.LiveJourney;
import com.mapidf.rt.RtSnapshot.LiveJourney.Call;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class StationDepartureServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-22T08:00:00Z");
    private final StationDepartureService service = new StationDepartureService();

    private static LiveJourney journey(String dest, Call... calls) {
        return journey(dest, "0", dest, calls);
    }

    private static LiveJourney journey(String dest, String directionRef, String journeyRef, Call... calls) {
        return new LiveJourney("STIF:Line::C01379:", journeyRef, directionRef, dest, List.of(calls));
    }

    private static Call call(String ref, Instant t) {
        return new Call(ref, t, "ON_TIME");
    }

    @Test
    void groupsByDestinationSortsByTimeAndCapsPerDirection() {
        // Station = quai "463641". Deux courses vers "Montreuil" et une vers "Pont de Sèvres".
        Set<String> keys = Set.of("463641");
        List<LiveJourney> journeys = List.of(
            journey("Montreuil", call("STIF:StopPoint:Q:463641:", NOW.plusSeconds(360))),  // 6 min
            journey("Montreuil", call("STIF:StopPoint:Q:463641:", NOW.plusSeconds(120))),  // 2 min
            journey("Montreuil", call("STIF:StopPoint:Q:463641:", NOW.plusSeconds(600))),  // 10 min
            journey("Montreuil", call("STIF:StopPoint:Q:463641:", NOW.plusSeconds(900))),  // 15 min (4e → coupé)
            journey("Pont de Sèvres", call("STIF:StopPoint:Q:463641:", NOW.plusSeconds(200))));

        DeparturesResponse r = service.departures("Havre-Caumartin", keys, journeys, NOW, 3);

        assertThat(r.stationName()).isEqualTo("Havre-Caumartin");
        assertThat(r.directions()).hasSize(2);
        DeparturesResponse.Direction montreuil = r.directions().stream()
            .filter(d -> d.destination().equals("Montreuil")).findFirst().orElseThrow();
        // trié par heure, cap à 3 : 2 / 6 / 10 min
        assertThat(montreuil.passages()).extracting(DeparturesResponse.Passage::expectedTime)
            .containsExactly(NOW.plusSeconds(120), NOW.plusSeconds(360), NOW.plusSeconds(600));
        // journeyRef propagé sur chaque passage (permet le clic → suivi côté front)
        assertThat(montreuil.passages()).allSatisfy(p -> assertThat(p.journeyRef()).isNotBlank());
    }

    @Test
    void excludesPassagesInThePastAndCallsAtOtherStops() {
        Set<String> keys = Set.of("463641");
        List<LiveJourney> journeys = List.of(
            journey("Montreuil",
                call("STIF:StopPoint:Q:463641:", NOW.minusSeconds(60)),   // passé → exclu
                call("STIF:StopPoint:Q:463641:", NOW.plusSeconds(180))),  // futur → gardé
            journey("Montreuil", call("STIF:StopPoint:Q:999999:", NOW.plusSeconds(90)))); // autre arrêt → exclu

        DeparturesResponse r = service.departures("X", keys, journeys, NOW, 3);

        assertThat(r.directions()).hasSize(1);
        assertThat(r.directions().getFirst().passages()).hasSize(1);
    }

    @Test
    void returnsEmptyDirectionsWhenNoUpcomingPassage() {
        DeparturesResponse r = service.departures("X", Set.of("1"),
            List.of(journey("Montreuil", call("STIF:StopPoint:Q:1:", NOW.minusSeconds(10)))), NOW, 3);
        assertThat(r.directions()).isEmpty();
    }

    @Test
    void ordersDirectionsByDirectionRefThenDestination() {
        // Peu importe l'ordre du flux : direction 0 (Montreuil) avant direction 1 (Pont de Sèvres).
        List<LiveJourney> journeys = List.of(
            journey("Pont de Sèvres", "1", "jA", call("STIF:StopPoint:Q:1:", NOW.plusSeconds(120))),
            journey("Mairie de Montreuil", "0", "jB", call("STIF:StopPoint:Q:1:", NOW.plusSeconds(60))));

        DeparturesResponse r = service.departures("X", Set.of("1"), journeys, NOW, 3);

        assertThat(r.directions()).extracting(DeparturesResponse.Direction::destination)
            .containsExactly("Mairie de Montreuil", "Pont de Sèvres");
    }
}
