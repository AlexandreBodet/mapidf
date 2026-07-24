package com.mapidf.position;

import java.time.Instant;
import java.util.List;

import com.mapidf.rt.RtSnapshot.LiveJourney;
import com.mapidf.rt.RtSnapshot.LiveJourney.Call;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.PrecisionModel;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class PositionEngineTest {

    private static final Instant NOW = Instant.parse("2026-07-22T08:05:00Z");

    private final PositionEngine engine = new PositionEngine();

    private static LineString line() {
        GeometryFactory gf = new GeometryFactory(new PrecisionModel(), 4326);
        return gf.createLineString(new Coordinate[]{
            new Coordinate(2.300, 48.850), new Coordinate(2.310, 48.850), new Coordinate(2.320, 48.850)});
    }

    // sens "Gamma" : Alpha(0) → Beta(0.010) → Gamma(0.020), départs 08:00 / 08:10 / 08:20
    private static LineSchedule towardGamma() {
        return new LineSchedule(List.of(new DirectionSchedule("Gamma", List.of(
            new StopOnLine("1", "Alpha", 0.000, 8 * 3600),
            new StopOnLine("2", "Beta", 0.010, 8 * 3600 + 600),
            new StopOnLine("3", "Gamma", 0.020, 8 * 3600 + 1200)))));
    }

    private static Call call(String stopRef, Instant time) {
        return new Call(stopRef, time, "ON_TIME");
    }

    // Une course ligne 9 (dest "Gamma", sens 0, id "J1") avec la liste d'appels donnée.
    private static List<LiveJourney> rtWith(String destination, Call... calls) {
        return List.of(new LiveJourney("STIF:Line::C01379:", "J1", "0", destination, List.of(calls)));
    }

    @Test
    void interpolatesTowardNextStopUsingTheoreticalSegmentWhenNoPreviousCall() {
        // Un seul appel à venir : Beta, ETA dans 300 s. Pas d'arrêt passé dans le flux → on estime
        // l'heure de départ d'Alpha via l'horaire théorique (segment Alpha→Beta = 600 s) → mi-chemin.
        List<Vehicle> vehicles = engine.computeAll(line(), towardGamma(),
            rtWith("Gamma", call("STIF:StopPoint:Q:2:", NOW.plusSeconds(300))), NOW);

        assertThat(vehicles).hasSize(1);
        Vehicle v = vehicles.getFirst();
        assertThat(v.source()).isEqualTo(Vehicle.Source.INTERPOLATED);
        assertThat(v.tripId()).isEqualTo("J1");
        assertThat(v.lng()).isCloseTo(2.305, within(1e-3));
        assertThat(v.lat()).isCloseTo(48.850, within(1e-4));
        assertThat(v.nextStop()).isEqualTo("Beta");
        assertThat(v.headsign()).isEqualTo("Gamma");
        assertThat(v.bearing()).isCloseTo(90.0, within(5.0));
        assertThat(v.status()).isEqualTo("ON_TIME");
        assertThat(v.expectedTime()).isEqualTo(NOW.plusSeconds(300));
    }

    @Test
    void interpolatesUsingRealTimesWhenPreviousCallIsPresent() {
        // Le flux contient l'arrêt passé (Alpha, il y a 300 s) ET le prochain (Beta, dans 300 s).
        // On interpole aux VRAIES heures : à mi-chemin du segment réel Alpha→Beta → lng 2.305.
        Vehicle v = engine.computeAll(line(), towardGamma(),
            rtWith("Gamma",
                call("STIF:StopPoint:Q:1:", NOW.minusSeconds(300)),
                call("STIF:StopPoint:Q:2:", NOW.plusSeconds(300))), NOW).getFirst();

        assertThat(v.lng()).isCloseTo(2.305, within(1e-3));
        assertThat(v.nextStop()).isEqualTo("Beta");
    }

    @Test
    void realTimesCaptureDwellTime() {
        // Train à quai à Alpha : arrêt passé Alpha il y a 30 s, prochain Beta dans 570 s (long).
        // Aux vraies heures, fraction = 30/600 = 0.05 → encore quasi à Alpha (temps à quai capté).
        Vehicle v = engine.computeAll(line(), towardGamma(),
            rtWith("Gamma",
                call("STIF:StopPoint:Q:1:", NOW.minusSeconds(30)),
                call("STIF:StopPoint:Q:2:", NOW.plusSeconds(570))), NOW).getFirst();

        assertThat(v.lng()).isCloseTo(2.3005, within(5e-4)); // ~5 % du segment Alpha→Beta
        assertThat(v.nextStop()).isEqualTo("Beta");
    }

    @Test
    void picksEarliestUpcomingStopAmongUnorderedCalls() {
        // Appels dans le désordre : Gamma (dans 600 s) AVANT Beta (dans 300 s). Le prochain arrêt
        // doit être Beta (le plus tôt à venir), pas Gamma (premier du tableau).
        Vehicle v = engine.computeAll(line(), towardGamma(),
            rtWith("Gamma",
                call("STIF:StopPoint:Q:3:", NOW.plusSeconds(600)),
                call("STIF:StopPoint:Q:2:", NOW.plusSeconds(300))), NOW).getFirst();

        assertThat(v.nextStop()).isEqualTo("Beta");
        assertThat(v.lng()).isCloseTo(2.305, within(1e-3));
    }

    @Test
    void clampsToPreviousStopWhenOnlyFarCallAvailable() {
        // Sans arrêt passé et avec un unique arrêt lointain (Gamma dans 900 s, > un segment),
        // le train N'EST PLUS reculé (plus de walk-back) : il est borné à l'arrêt précédent Beta.
        Vehicle v = engine.computeAll(line(), towardGamma(),
            rtWith("Gamma", call("STIF:StopPoint:Q:3:", NOW.plusSeconds(900))), NOW).getFirst();

        assertThat(v.lng()).isCloseTo(2.310, within(1e-3)); // borné à Beta
        assertThat(v.nextStop()).isEqualTo("Gamma");
    }

    @Test
    void placesAtLastKnownStopWhenAllCallsArePassed() {
        // Tous les arrêts passés (Beta il y a 100 s) : train tout juste arrivé → placé à Beta,
        // jamais masqué.
        Vehicle v = engine.computeAll(line(), towardGamma(),
            rtWith("Gamma", call("STIF:StopPoint:Q:2:", NOW.minusSeconds(100))), NOW).getFirst();

        assertThat(v.lng()).isCloseTo(2.310, within(1e-3));
    }

    @Test
    void placesAtOriginWhenNextStopIsFirst() {
        Vehicle v = engine.computeAll(line(), towardGamma(),
            rtWith("Gamma", call("STIF:StopPoint:Q:1:", NOW.plusSeconds(30))), NOW).getFirst();

        assertThat(v.lng()).isCloseTo(2.300, within(1e-3));
        assertThat(v.nextStop()).isEqualTo("Alpha");
    }

    @Test
    void skipsJourneyWhenNextStopUnknown() {
        assertThat(engine.computeAll(line(), towardGamma(),
            rtWith("Gamma", call("STIF:StopPoint:Q:999:", NOW.plusSeconds(30))), NOW)).isEmpty();
    }

    @Test
    void selectsDirectionByDestinationWhenStopSharedByBothSenses() {
        // deux sens partagent l'arrêt "Beta" ; destination "Alpha" → sens retour (Gamma→Alpha)
        LineSchedule schedule = new LineSchedule(List.of(
            new DirectionSchedule("Gamma", List.of(
                new StopOnLine("1", "Alpha", 0.000, 8 * 3600),
                new StopOnLine("2", "Beta", 0.010, 8 * 3600 + 600),
                new StopOnLine("3", "Gamma", 0.020, 8 * 3600 + 1200))),
            new DirectionSchedule("Alpha", List.of(
                new StopOnLine("3", "Gamma", 0.020, 8 * 3600),
                new StopOnLine("2", "Beta", 0.010, 8 * 3600 + 600),
                new StopOnLine("1", "Alpha", 0.000, 8 * 3600 + 1200)))));
        List<LiveJourney> rt = List.of(new LiveJourney(
            "STIF:Line::C01379:", "J2", "1", "Alpha", List.of(call("STIF:StopPoint:Q:2:", NOW.plusSeconds(300)))));

        Vehicle v = engine.computeAll(line(), schedule, rt, NOW).getFirst();

        assertThat(v.lng()).isCloseTo(2.315, within(1e-3)); // entre Gamma(2.320) et Beta(2.310)
        assertThat(v.bearing()).isCloseTo(270.0, within(5.0)); // cap vers l'ouest
    }
}
