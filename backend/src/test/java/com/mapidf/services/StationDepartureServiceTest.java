package com.mapidf.services;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.mapidf.controllers.stations.DeparturesResponse;
import com.mapidf.network.Station;
import com.mapidf.network.TrackedLine;
import com.mapidf.rt.RtSnapshot;
import com.mapidf.rt.RtSnapshot.LiveJourney;
import com.mapidf.rt.RtSnapshot.LiveJourney.Call;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class StationDepartureServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-22T14:00:00Z");
    private static final String NINE = "STIF:Line::C01379:";
    private static final String SEVEN = "STIF:Line::C01377:";

    private final StationDepartureService service = new StationDepartureService();

    private static TrackedLine line(String id, String siriRef, String color) {
        return new TrackedLine(id, "IDFM:X" + id, siriRef, id, color, "METRO", List.of());
    }

    private static LiveJourney journey(String siriRef, String ref, String destination,
                                      String stopRef, Instant time, String status) {
        return new LiveJourney(siriRef, ref, "0", destination, NOW,
            List.of(new Call(stopRef, time, status)));
    }

    /** Station de correspondance : quai S2 pour la 9, quai P2 pour la 7. */
    private static Station correspondence() {
        return new Station("STC", "Correspondance", 48.850, 2.310,
            List.of("S2", "P2"), List.of("7", "9"));
    }

    @Test
    void groupsPassagesByLineThenDirection() {
        RtSnapshot snapshot = new RtSnapshot(NOW, Map.of(
            NINE, List.of(journey(NINE, "J9", "Gamma", "STIF:StopPoint:Q:2:",
                NOW.plusSeconds(120), "ON_TIME")),
            SEVEN, List.of(journey(SEVEN, "J7", "Ivry", "STIF:StopPoint:Q:2:",
                NOW.plusSeconds(180), "ON_TIME"))));

        DeparturesResponse response = service.departures(correspondence(),
            List.of(line("7", SEVEN, "#FF82B4"), line("9", NINE, "#D2D200")), snapshot, NOW, 3);

        assertThat(response.stationName()).isEqualTo("Correspondance");
        assertThat(response.lines()).extracting(DeparturesResponse.LineDepartures::lineId)
            .containsExactly("7", "9");
        assertThat(response.lines().getFirst().color()).isEqualTo("#FF82B4");
        assertThat(response.lines().getFirst().directions())
            .singleElement()
            .extracting(DeparturesResponse.Direction::destination).isEqualTo("Ivry");
    }

    @Test
    void keepsBothDirectionsOfTheSameLine() {
        // La fusion des deux sens à une station est le comportement attendu : la station résout
        // tous ses quais, donc les deux sens, chacun devenant un groupe de destination.
        RtSnapshot snapshot = new RtSnapshot(NOW, Map.of(NINE, List.of(
            journey(NINE, "J1", "Gamma", "STIF:StopPoint:Q:2:", NOW.plusSeconds(60), "ON_TIME"),
            journey(NINE, "J2", "Alpha", "STIF:StopPoint:Q:2:", NOW.plusSeconds(90), "ON_TIME"))));

        DeparturesResponse response = service.departures(correspondence(),
            List.of(line("9", NINE, "#D2D200")), snapshot, NOW, 3);

        assertThat(response.lines()).singleElement()
            .extracting(DeparturesResponse.LineDepartures::directions).asInstanceOf(
                org.assertj.core.api.InstanceOfAssertFactories.list(DeparturesResponse.Direction.class))
            .extracting(DeparturesResponse.Direction::destination)
            .containsExactlyInAnyOrder("Gamma", "Alpha");
    }

    @Test
    void ordersLinesByNumberNotAlphabetically() {
        // Ordre humain attendu : 3, 3b, 7, 9, 14 — pas 14, 3, 3b, 7, 9.
        // Chaque ligne a un passage à venir : une ligne sans passage disparaît du payload (voir
        // ignoresPassagesAlreadyGoneAndStopsOfOtherStations), donc un snapshot vide ne pourrait
        // pas exercer l'ordre de tri sur les cinq lignes.
        String l14 = "L14";
        String l3 = "L3";
        String l3b = "L3B";
        String l7 = "L7";
        String l9 = "L9";
        RtSnapshot snapshot = new RtSnapshot(NOW, Map.of(
            l14, List.of(journey(l14, "J14", "Gamma", "STIF:StopPoint:Q:2:", NOW.plusSeconds(60), "ON_TIME")),
            l3, List.of(journey(l3, "J3", "Gamma", "STIF:StopPoint:Q:2:", NOW.plusSeconds(60), "ON_TIME")),
            l3b, List.of(journey(l3b, "J3b", "Gamma", "STIF:StopPoint:Q:2:", NOW.plusSeconds(60), "ON_TIME")),
            l7, List.of(journey(l7, "J7", "Gamma", "STIF:StopPoint:Q:2:", NOW.plusSeconds(60), "ON_TIME")),
            l9, List.of(journey(l9, "J9", "Gamma", "STIF:StopPoint:Q:2:", NOW.plusSeconds(60), "ON_TIME"))));

        DeparturesResponse response = service.departures(
            new Station("ST", "Multi", 0, 0, List.of("S2"),
                List.of("14", "3", "3b", "7", "9")),
            List.of(line("14", l14, "#640082"), line("3", l3, "#6E6E00"),
                    line("3b", l3b, "#82C8E6"), line("7", l7, "#FF82B4"),
                    line("9", l9, "#D2D200")),
            snapshot, NOW, 3);

        assertThat(response.lines()).extracting(DeparturesResponse.LineDepartures::lineId)
            .containsExactly("3", "3b", "7", "9", "14");
    }

    @Test
    void ignoresPassagesAlreadyGoneAndStopsOfOtherStations() {
        RtSnapshot snapshot = new RtSnapshot(NOW, Map.of(NINE, List.of(
            journey(NINE, "PAST", "Gamma", "STIF:StopPoint:Q:2:", NOW.minusSeconds(60), "ON_TIME"),
            journey(NINE, "ELSEWHERE", "Gamma", "STIF:StopPoint:Q:99:", NOW.plusSeconds(60), "ON_TIME"))));

        DeparturesResponse response = service.departures(correspondence(),
            List.of(line("9", NINE, "#D2D200")), snapshot, NOW, 3);

        assertThat(response.lines()).isEmpty();
    }

    @Test
    void limitsThePassagesPerDirectionAndSortsThemByTime() {
        RtSnapshot snapshot = new RtSnapshot(NOW, Map.of(NINE, List.of(
            journey(NINE, "C", "Gamma", "STIF:StopPoint:Q:2:", NOW.plusSeconds(300), "ON_TIME"),
            journey(NINE, "A", "Gamma", "STIF:StopPoint:Q:2:", NOW.plusSeconds(60), "ON_TIME"),
            journey(NINE, "B", "Gamma", "STIF:StopPoint:Q:2:", NOW.plusSeconds(180), "ON_TIME"),
            journey(NINE, "D", "Gamma", "STIF:StopPoint:Q:2:", NOW.plusSeconds(600), "ON_TIME"))));

        DeparturesResponse response = service.departures(correspondence(),
            List.of(line("9", NINE, "#D2D200")), snapshot, NOW, 3);

        assertThat(response.lines().getFirst().directions().getFirst().passages())
            .extracting(DeparturesResponse.Passage::journeyRef)
            .containsExactly("A", "B", "C");
    }

    @Test
    void carriesTheDelayedStatusThrough() {
        // Mesuré sur une ligne 8 perturbée : 14 % de ses appels en DELAYED, le taux le plus
        // élevé du réseau. Le statut doit remonter pour que le front l'affiche enfin.
        RtSnapshot snapshot = new RtSnapshot(NOW, Map.of(NINE, List.of(
            journey(NINE, "J1", "Gamma", "STIF:StopPoint:Q:2:", NOW.plusSeconds(60), "DELAYED"))));

        DeparturesResponse response = service.departures(correspondence(),
            List.of(line("9", NINE, "#D2D200")), snapshot, NOW, 3);

        assertThat(response.lines().getFirst().directions().getFirst().passages())
            .singleElement()
            .extracting(DeparturesResponse.Passage::status).isEqualTo("DELAYED");
    }
}
