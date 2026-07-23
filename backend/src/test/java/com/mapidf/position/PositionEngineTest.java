package com.mapidf.position;

import java.time.Instant;
import java.util.List;

import com.mapidf.rt.RtSnapshot.LiveJourney;
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

    private static LiveJourney journey(String destination, String stopRef, Instant eta) {
        return new LiveJourney("STIF:Line::C01379:", "J1", "0", destination, stopRef, eta, "ON_TIME");
    }

    // Le moteur travaille désormais sur la liste de courses d'UNE ligne (extraite du snapshot réseau).
    private static List<LiveJourney> rtWith(LiveJourney journey) {
        return List.of(journey);
    }

    @Test
    void interpolatesTowardNextStopUsingEta() {
        // prochain arrêt Beta, ETA dans 300 s, segment Alpha→Beta = 600 s → à mi-chemin (lng 2.305)
        LiveJourney j = journey("Gamma", "STIF:StopPoint:Q:2:", NOW.plusSeconds(300));

        List<Vehicle> vehicles = engine.computeAll(line(), towardGamma(), rtWith(j), NOW);

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
    }

    @Test
    void clampsToNextStopWhenEtaAlreadyPassed() {
        LiveJourney j = journey("Gamma", "STIF:StopPoint:Q:2:", NOW.minusSeconds(100));

        Vehicle v = engine.computeAll(line(), towardGamma(), rtWith(j), NOW).getFirst();

        assertThat(v.lng()).isCloseTo(2.310, within(1e-3)); // arrivé à Beta
    }

    @Test
    void interpolatesAcrossMultipleSegmentsWhenEtaSpansMoreThanOneSegment() {
        // prochain arrêt reporté = Gamma (2 segments plus loin), ETA dans 900 s.
        // segment Beta→Gamma = 600 s (consommé) ; reste 300 s sur Alpha→Beta (600 s)
        // → fraction 0.5 sur Alpha→Beta → lng 2.305, prochain arrêt affiché = Gamma.
        LiveJourney j = journey("Gamma", "STIF:StopPoint:Q:3:", NOW.plusSeconds(900));

        Vehicle v = engine.computeAll(line(), towardGamma(), rtWith(j), NOW).getFirst();

        assertThat(v.lng()).isCloseTo(2.305, within(1e-3));
        assertThat(v.nextStop()).isEqualTo("Gamma");
    }

    @Test
    void placesAtOriginWhenNextStopIsFirst() {
        LiveJourney j = journey("Gamma", "STIF:StopPoint:Q:1:", NOW.plusSeconds(30));

        Vehicle v = engine.computeAll(line(), towardGamma(), rtWith(j), NOW).getFirst();

        assertThat(v.lng()).isCloseTo(2.300, within(1e-3));
        assertThat(v.nextStop()).isEqualTo("Alpha");
    }

    @Test
    void skipsJourneyWhenNextStopUnknown() {
        LiveJourney j = journey("Gamma", "STIF:StopPoint:Q:999:", NOW.plusSeconds(30));

        assertThat(engine.computeAll(line(), towardGamma(), rtWith(j), NOW)).isEmpty();
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
        LiveJourney j = new LiveJourney(
            "STIF:Line::C01379:", "J2", "1", "Alpha", "STIF:StopPoint:Q:2:", NOW.plusSeconds(300), "ON_TIME");

        Vehicle v = engine.computeAll(line(), schedule, rtWith(j), NOW).getFirst();

        assertThat(v.lng()).isCloseTo(2.315, within(1e-3)); // entre Gamma(2.320) et Beta(2.310)
        assertThat(v.bearing()).isCloseTo(270.0, within(5.0)); // cap vers l'ouest
    }
}
