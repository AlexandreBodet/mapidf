package com.mapidf.position;

import java.time.Instant;
import java.util.List;

import com.mapidf.network.LineBranch;
import com.mapidf.network.TrackedLine;
import com.mapidf.rt.RtSnapshot.LiveJourney;
import com.mapidf.rt.RtSnapshot.LiveJourney.Call;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.PrecisionModel;
import static org.assertj.core.api.Assertions.assertThat;

class PositionEngineTest {

    private static final GeometryFactory GF = new GeometryFactory(new PrecisionModel(), 4326);
    private static final Instant NOW = Instant.parse("2026-07-22T14:00:00Z");
    private final PositionEngine engine = new PositionEngine();

    // Tronc P1(48.870) -> P2(48.850) -> P3(48.840), puis divergence :
    // branche "Villejuif" vers P4(2.300), branche "Ivry" vers P5(2.320).
    private static LineString shape(double lastLon) {
        return GF.createLineString(new Coordinate[]{
            new Coordinate(2.310, 48.870), new Coordinate(2.310, 48.850),
            new Coordinate(2.310, 48.840), new Coordinate(lastLon, 48.830)});
    }

    private static LineBranch branch(String shapeId, String terminus, String lastStopKey, double lastLon) {
        return LineBranch.of(shapeId, (short) 0, terminus, shape(lastLon), List.of(
            new StopOnLine("1", "Nord", 0.000, 0),
            new StopOnLine("2", "Correspondance", 0.020, 240),
            new StopOnLine("3", "Sud", 0.030, 480),
            new StopOnLine(lastStopKey, terminus, 0.045, 720)));
    }

    private static TrackedLine branchedLine() {
        return new TrackedLine("7", "IDFM:C01377", "STIF:Line::C01377:", "7", "#FF82B4", "METRO",
            List.of(branch("SH7A", "Villejuif", "4", 2.300),
                    branch("SH7B", "Ivry", "5", 2.320)));
    }

    private static LiveJourney journey(String destination, List<Call> calls) {
        return new LiveJourney("STIF:Line::C01377:", "J1", "0", destination, NOW.minusSeconds(30), calls);
    }

    @Test
    void picksTheBranchWhoseTerminusMatchesTheDestination() {
        // Arrêt commun, mais situé APRÈS la bifurcation (contrairement à "Sud", sur le tronc
        // partagé où la géométrie des deux branches est identique) : seule la destination
        // permet de trancher, et le choix se voit ensuite dans la position interpolée. Avec un
        // arrêt commun resté sur le tronc, n'importe quelle branche donnerait la même longitude
        // (vérifié : la fixture d'origine du brief interpole à une distance de 0,0275, avant la
        // bifurcation à 0,030 — la longitude vaut alors 2.310 quelle que soit la branche choisie,
        // et l'assertion ci-dessous ne pouvait jamais mordre). Ici l'arrêt "6" est placé après la
        // bifurcation : sa position réelle diffère selon la branche retenue.
        LineBranch villejuif = branchPastFork("SH7A", "Villejuif", "4", 2.300);
        LineBranch ivry = branchPastFork("SH7B", "Ivry", "5", 2.320);
        TrackedLine line = new TrackedLine("7", "IDFM:C01377", "STIF:Line::C01377:", "7",
            "#FF82B4", "METRO", List.of(villejuif, ivry));
        LiveJourney toIvry = journey("Ivry", List.of(
            new Call("STIF:StopPoint:Q:6:", NOW.plusSeconds(60), "ON_TIME"),
            new Call("STIF:StopPoint:Q:5:", NOW.plusSeconds(300), "ON_TIME")));

        List<Vehicle> vehicles = engine.computeAll(line, List.of(toIvry), NOW);

        assertThat(vehicles).singleElement()
            .satisfies(v -> assertThat(v.headsign()).isEqualTo("Ivry"));
        // Le train est entre Sud et Ivry, après la bifurcation : sa longitude tend vers 2.320,
        // pas vers 2.300. Sans le bon choix de branche, elle serait < 2.310 (côté Villejuif).
        assertThat(vehicles.getFirst().lng()).isGreaterThan(2.310);
    }

    // Variante de branch() avec un arrêt commun "6" situé après la bifurcation (entre "Sud" et
    // le terminus), utilisée uniquement par picksTheBranchWhoseTerminusMatchesTheDestination
    // pour que le choix de branche soit observable sur la position, pas seulement sur le headsign.
    private static LineBranch branchPastFork(String shapeId, String terminus, String lastStopKey, double lastLon) {
        return LineBranch.of(shapeId, (short) 0, terminus, shape(lastLon), List.of(
            new StopOnLine("1", "Nord", 0.000, 0),
            new StopOnLine("2", "Correspondance", 0.020, 240),
            new StopOnLine("3", "Sud", 0.030, 480),
            new StopOnLine("6", "Bifurcation", 0.038, 600),
            new StopOnLine(lastStopKey, terminus, 0.045, 720)));
    }

    @Test
    void picksTheOnlyBranchThatServesTheNextStop() {
        // L'arrêt 4 n'existe que sur la branche Villejuif : aucun départage nécessaire.
        LiveJourney toVillejuif = journey("Inconnu", List.of(
            new Call("STIF:StopPoint:Q:4:", NOW.plusSeconds(120), "ON_TIME")));

        List<Vehicle> vehicles = engine.computeAll(branchedLine(), List.of(toVillejuif), NOW);

        assertThat(vehicles).singleElement()
            .satisfies(v -> assertThat(v.lng()).isLessThan(2.310));
    }

    @Test
    void dropsAJourneyWhoseNextStopIsOnNoBranch() {
        // Mesuré : 0,6 % du flux métro après couverture gloutonne — des StopPointRef SIRI
        // absents du GTFS. Doit être écarté proprement, pas lever.
        LiveJourney orphan = journey("Ailleurs", List.of(
            new Call("STIF:StopPoint:Q:999:", NOW.plusSeconds(60), "ON_TIME")));

        assertThat(engine.computeAll(branchedLine(), List.of(orphan), NOW)).isEmpty();
    }

    @Test
    void flagsASingleCallJourneyAsApproximate() {
        // Signal STRUCTUREL, jamais un seuil d'ETA : 36 % des courses métro n'ont qu'un appel
        // et sont bornées à l'arrêt précédant leur unique appel, souvent un terminus lointain.
        LiveJourney single = journey("Ivry", List.of(
            new Call("STIF:StopPoint:Q:5:", NOW.plusSeconds(900), "ON_TIME")));

        assertThat(engine.computeAll(branchedLine(), List.of(single), NOW))
            .singleElement()
            .extracting(Vehicle::confidence).isEqualTo(Vehicle.Confidence.APPROXIMATE);
    }

    @Test
    void marksAMultiCallJourneyAsReliable() {
        LiveJourney multi = journey("Ivry", List.of(
            new Call("STIF:StopPoint:Q:3:", NOW.plusSeconds(60), "ON_TIME"),
            new Call("STIF:StopPoint:Q:5:", NOW.plusSeconds(300), "ON_TIME")));

        assertThat(engine.computeAll(branchedLine(), List.of(multi), NOW))
            .singleElement()
            .extracting(Vehicle::confidence).isEqualTo(Vehicle.Confidence.RELIABLE);
    }

    @Test
    void carriesTheLineIdAndTheRecordedAtOfTheJourney() {
        LiveJourney multi = journey("Ivry", List.of(
            new Call("STIF:StopPoint:Q:3:", NOW.plusSeconds(60), "ON_TIME"),
            new Call("STIF:StopPoint:Q:5:", NOW.plusSeconds(300), "ON_TIME")));

        assertThat(engine.computeAll(branchedLine(), List.of(multi), NOW)).singleElement()
            .satisfies(v -> {
                assertThat(v.lineId()).isEqualTo("7");
                assertThat(v.journeyRef()).isEqualTo("J1");
                assertThat(v.recordedAt()).isEqualTo(NOW.minusSeconds(30));
            });
    }

    @Test
    void picksTheEarliestUpcomingCallEvenWhenCallsAreUnordered() {
        // Les EstimatedCall ne sont PAS triés et n'ont pas de champ Order (vérifié sur le flux
        // réel) : le prochain arrêt est le plus tôt À VENIR, pas le premier du tableau.
        LiveJourney unordered = journey("Ivry", List.of(
            new Call("STIF:StopPoint:Q:5:", NOW.plusSeconds(600), "ON_TIME"),
            new Call("STIF:StopPoint:Q:3:", NOW.plusSeconds(60), "ON_TIME"),
            new Call("STIF:StopPoint:Q:2:", NOW.minusSeconds(120), "ON_TIME")));

        assertThat(engine.computeAll(branchedLine(), List.of(unordered), NOW))
            .singleElement()
            .extracting(Vehicle::nextStop).isEqualTo("Sud");
    }

    @Test
    void extractsTheLastDigitGroupAsStopKey() {
        assertThat(PositionEngine.stopKey("STIF:StopPoint:Q:463221:")).isEqualTo("463221");
        assertThat(PositionEngine.stopKey("IDFM:463221")).isEqualTo("463221");
        assertThat(PositionEngine.stopKey("IDFM:StopPoint:59:463221")).isEqualTo("463221");
        assertThat(PositionEngine.stopKey(null)).isEmpty();
    }
}
