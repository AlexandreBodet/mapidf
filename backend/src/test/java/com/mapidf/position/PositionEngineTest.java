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
import static org.assertj.core.api.Assertions.within;

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

    // Sens retour (direction 1) de la même route : NetworkRegistryBuilder place TOUTES les
    // directions dans TrackedLine.branches(), donc un train approchant un arrêt de tronc a
    // aussi la direction retour comme candidate. Même arrêt "3" ("Sud"), parcouru en sens
    // inverse jusqu'au terminus "Nord" (repris ici comme dernier arrêt, "Nord" étant déjà le nom
    // du premier arrêt du sens aller — ce qui est le cas réel : le terminus d'un sens est
    // souvent le premier arrêt de l'autre).
    private static LineBranch returnBranch() {
        LineString reversed = GF.createLineString(new Coordinate[]{
            new Coordinate(2.310, 48.840), new Coordinate(2.310, 48.850), new Coordinate(2.310, 48.870)});
        return LineBranch.of("SH7RET", (short) 1, "Nord", reversed, List.of(
            new StopOnLine("3", "Sud", 0.000, 0),
            new StopOnLine("2", "Correspondance", 0.010, 200),
            new StopOnLine("1", "Nord", 0.030, 500)));
    }

    private static TrackedLine branchedLine() {
        return new TrackedLine("line-7", "IDFM:C01377", "STIF:Line::C01377:", "7", "#FF82B4", "METRO",
            List.of(branch("SH7A", "Villejuif", "4", 2.300),
                    branch("SH7B", "Ivry", "5", 2.320)));
    }

    private static LiveJourney journey(String destination, List<Call> calls) {
        return new LiveJourney("STIF:Line::C01377:", "J1", "0", destination, NOW.minusSeconds(30), calls);
    }

    @Test
    void doesNotPlaceATrainOnTheReturnBranchOfTheSameRoute() {
        // Cas réel le plus coûteux (cf. revue) : NetworkRegistryBuilder met toutes les
        // directions d'une route dans branches(), donc pour un arrêt de tronc les candidates
        // incluent la branche retour. Mal départagée, le train serait placé en sens inverse
        // (vers Nord) au lieu de continuer vers Ivry — jusqu'à la longueur de la ligne d'écart,
        // bien pire que le mélange Villejuif/Ivry (1547 m mesurés). La branche retour est placée
        // EN PREMIER dans la liste pour que le test morde même sur un pickBranch qui renverrait
        // naïvement le premier candidat.
        TrackedLine line = new TrackedLine("line-7", "IDFM:C01377", "STIF:Line::C01377:", "7",
            "#FF82B4", "METRO", List.of(
                returnBranch(),
                branch("SH7A", "Villejuif", "4", 2.300),
                branch("SH7B", "Ivry", "5", 2.320)));
        LiveJourney toIvry = journey("Ivry", List.of(
            new Call("STIF:StopPoint:Q:3:", NOW.plusSeconds(60), "ON_TIME"),
            new Call("STIF:StopPoint:Q:5:", NOW.plusSeconds(300), "ON_TIME")));

        List<Vehicle> vehicles = engine.computeAll(line, List.of(toIvry), NOW);

        assertThat(vehicles).singleElement()
            .satisfies(v -> assertThat(v.headsign()).isEqualTo("Ivry"));
        // Sur la branche retour, l'arrêt "3" est en tête (nextIdx == 0) : le cap calculé
        // pointerait vers Correspondance, donc au nord (~0°). En continuant vers Ivry sur le
        // tronc partagé (de Correspondance vers Sud), le cap reste au sud (~180°).
        assertThat(vehicles.getFirst().bearing()).isCloseTo(180.0, within(1.0));
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
        // L'appel est ici volontairement LOINTAIN (900s) : la fraction brute d'interpolation
        // vaut alors -2,75 (calcul détaillé dans le rapport de tâche), hors de [0,1]. Sans le
        // clamp, extractPoint interpréterait cet index négatif comme une distance depuis la FIN
        // de la ligne et téléporterait le train près du terminus, au lieu de le laisser à Sud.
        LiveJourney single = journey("Ivry", List.of(
            new Call("STIF:StopPoint:Q:5:", NOW.plusSeconds(900), "ON_TIME")));

        assertThat(engine.computeAll(branchedLine(), List.of(single), NOW))
            .singleElement()
            .satisfies(v -> {
                assertThat(v.confidence()).isEqualTo(Vehicle.Confidence.APPROXIMATE);
                assertThat(v.lng()).isCloseTo(2.310, within(0.001));
                assertThat(v.lat()).isCloseTo(48.840, within(0.001));
            });
    }

    @Test
    void flagsAnImminentSingleCallJourneyAsApproximateToo() {
        // Ferme le seuil d'ETA dans le sens "proche" : un calcul confondu avec un seuil temporel
        // (ex. confidence = eta > 600s ? APPROXIMATE : RELIABLE) classerait cet appel imminent
        // (60s) comme RELIABLE. Le signal est purement structurel (un seul appel), donc
        // APPROXIMATE quelle que soit l'ETA.
        LiveJourney single = journey("Ivry", List.of(
            new Call("STIF:StopPoint:Q:5:", NOW.plusSeconds(60), "ON_TIME")));

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
    void marksADistantMultiCallJourneyAsReliableToo() {
        // Ferme le seuil d'ETA dans le sens "lointain" : un calcul confondu avec un seuil
        // temporel classerait ce prochain arrêt à 900s comme APPROXIMATE. Le signal est
        // purement structurel (deux appels), donc RELIABLE quelle que soit l'ETA.
        LiveJourney multiFar = journey("Ivry", List.of(
            new Call("STIF:StopPoint:Q:3:", NOW.plusSeconds(900), "ON_TIME"),
            new Call("STIF:StopPoint:Q:5:", NOW.plusSeconds(950), "ON_TIME")));

        assertThat(engine.computeAll(branchedLine(), List.of(multiFar), NOW))
            .singleElement()
            .extracting(Vehicle::confidence).isEqualTo(Vehicle.Confidence.RELIABLE);
    }

    @Test
    void clampsToTheLastKnownStopWhenAllCallsHavePassed() {
        // Symétrique de flagsASingleCallJourneyAsApproximate : tous les appels sont PASSÉS, donc
        // "next" retombe sur le dernier connu (repli sorted.getLast()) et la fraction calculée
        // sur l'horaire théorique dépasse 1 (train "en retard" sur son horaire). Sans clamp, le
        // train dépasserait Sud et serait projeté au-delà — jusqu'au terminus de la géométrie.
        // Avec clamp, il reste au dernier arrêt connu (Sud), pas au-delà.
        LiveJourney allPassed = journey("Ivry", List.of(
            new Call("STIF:StopPoint:Q:3:", NOW.minusSeconds(600), "ON_TIME")));

        assertThat(engine.computeAll(branchedLine(), List.of(allPassed), NOW))
            .singleElement()
            .satisfies(v -> {
                assertThat(v.lng()).isCloseTo(2.310, within(0.001));
                assertThat(v.lat()).isCloseTo(48.840, within(0.001));
            });
    }

    @Test
    void carriesTheLineIdAndTheRecordedAtOfTheJourney() {
        LiveJourney multi = journey("Ivry", List.of(
            new Call("STIF:StopPoint:Q:3:", NOW.plusSeconds(60), "ON_TIME"),
            new Call("STIF:StopPoint:Q:5:", NOW.plusSeconds(300), "ON_TIME")));

        assertThat(engine.computeAll(branchedLine(), List.of(multi), NOW)).singleElement()
            .satisfies(v -> {
                assertThat(v.lineId()).isEqualTo("line-7");
                assertThat(v.journeyRef()).isEqualTo("J1");
                assertThat(v.recordedAt()).isEqualTo(NOW.minusSeconds(30));
            });
    }

    @Test
    void interpolatesTowardNextStopUsingTheoreticalSegmentWhenNoPreviousCall() {
        // Les EstimatedCall sont TOUS à venir (cas courant : SIRI n'envoie pas de RecordedCalls),
        // donc aucune heure réelle de départ n'est disponible pour l'arrêt d'amont. L'origine du
        // segment est alors l'arrêt précédent du tracé, et son heure de départ est estimée par
        // l'ÉCART D'HORAIRE THÉORIQUE issu de stop_time — la seule utilité fonctionnelle des
        // 915 lignes que le loader persiste.
        //
        // Nord(0.000, 0 s) → Correspondance(0.020, 240 s) : segment théorique de 240 s. Prochain
        // arrêt Correspondance annoncé dans 120 s, donc le train est parti il y a 120 s et se
        // trouve à la moitié du segment : distance 0.010, soit lat 48.860 (mi-chemin entre
        // 48.870 et 48.850). Un moteur qui ignorerait scheduledSec (segment forcé à 1 s) placerait
        // le train à Nord, 48.870.
        LiveJourney allUpcoming = journey("Ivry", List.of(
            new Call("STIF:StopPoint:Q:2:", NOW.plusSeconds(120), "ON_TIME"),
            new Call("STIF:StopPoint:Q:3:", NOW.plusSeconds(360), "ON_TIME")));

        assertThat(engine.computeAll(branchedLine(), List.of(allUpcoming), NOW))
            .singleElement()
            .satisfies(v -> {
                assertThat(v.nextStop()).isEqualTo("Correspondance");
                assertThat(v.lat()).isCloseTo(48.860, within(1e-6));
                assertThat(v.lng()).isCloseTo(2.310, within(1e-6));
            });
    }

    @Test
    void interpolatesUsingRealTimesWhenPreviousCallIsPresent() {
        // Ici le flux porte un arrêt DÉJÀ PASSÉ en amont sur la branche (Nord il y a 100 s) :
        // l'origine du segment devient cet arrêt et son heure RÉELLE, pas l'horaire théorique.
        // Segment réel Nord(0.000) → Sud(0.030) de 400 s, dont 100 s écoulées → fraction 0.25,
        // distance 0.0075, soit lat 48.8625.
        //
        // Sur l'horaire théorique, l'origine serait Correspondance (l'arrêt de tracé qui précède
        // Sud) et la fraction serait négative donc bornée à 0 : le train serait figé à
        // Correspondance, 48.850. Les deux règles ne coïncident donc pas sur cette fixture.
        LiveJourney withPast = journey("Ivry", List.of(
            new Call("STIF:StopPoint:Q:1:", NOW.minusSeconds(100), "ON_TIME"),
            new Call("STIF:StopPoint:Q:3:", NOW.plusSeconds(300), "ON_TIME")));

        assertThat(engine.computeAll(branchedLine(), List.of(withPast), NOW))
            .singleElement()
            .satisfies(v -> {
                assertThat(v.nextStop()).isEqualTo("Sud");
                assertThat(v.lat()).isCloseTo(48.8625, within(1e-6));
            });
    }

    @Test
    void realTimesCaptureDwellTime() {
        // Ce que les vraies heures apportent par-dessus l'horaire théorique : le temps à quai.
        // Le train a quitté Nord il y a 30 s et n'atteint Correspondance que dans 570 s — un
        // segment réel de 600 s là où le théorique n'en prévoit que 240. C'est l'écart de 360 s
        // que le train vient de passer à quai. Fraction 30/600 = 0.05, donc lat 48.869 : le train
        // a mesurablement quitté Nord, mais de 5 % du segment seulement.
        //
        // Sur l'horaire théorique, l'origine serait la même (Nord) mais l'heure de départ estimée
        // tomberait 330 s dans le FUTUR (570 − 240) : fraction négative, bornée à 0, train figé
        // exactement à Nord (48.870). L'assertion à 1e-6 distingue donc bien les deux règles.
        LiveJourney dwelling = journey("Ivry", List.of(
            new Call("STIF:StopPoint:Q:1:", NOW.minusSeconds(30), "ON_TIME"),
            new Call("STIF:StopPoint:Q:2:", NOW.plusSeconds(570), "ON_TIME")));

        assertThat(engine.computeAll(branchedLine(), List.of(dwelling), NOW))
            .singleElement()
            .satisfies(v -> {
                assertThat(v.nextStop()).isEqualTo("Correspondance");
                assertThat(v.lat()).isCloseTo(48.869, within(1e-6));
            });
    }

    @Test
    void placesAtOriginWhenNextStopIsFirst() {
        // Prochain arrêt = tête de branche : il n'y a aucun segment en amont sur lequel
        // interpoler. Le train doit être placé à l'origine du tracé (lat 48.870), et surtout PAS
        // abandonné — un train qui entre en ligne resterait sinon invisible jusqu'à son deuxième
        // arrêt, exactement le genre de disparition que la décision produit interdit.
        LiveJourney atHead = journey("Ivry", List.of(
            new Call("STIF:StopPoint:Q:1:", NOW.plusSeconds(30), "ON_TIME")));

        assertThat(engine.computeAll(branchedLine(), List.of(atHead), NOW))
            .singleElement()
            .satisfies(v -> {
                assertThat(v.nextStop()).isEqualTo("Nord");
                assertThat(v.lat()).isCloseTo(48.870, within(1e-6));
                assertThat(v.lng()).isCloseTo(2.310, within(1e-6));
                // Le cap vient du segment tête → arrêt SUIVANT (0.000 → 0.020), donc plein sud.
                // Réduit à `after = to`, extractPoint renverrait deux fois le même point et le
                // cap tomberait à 0° (nord) : la flèche pointerait à l'envers.
                assertThat(v.bearing()).isCloseTo(180.0, within(1e-6));
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
    void terminusMatchesIsCaseInsensitiveAndAllowsPartialInclusionButNeverNull() {
        // Sémantique documentée sur terminusMatches : une implémentation réduite à
        // a.equals(b) passerait à côté de l'inclusion, précisément ce qui absorbe les écarts de
        // libellés entre SIRI et GTFS en production.
        assertThat(PositionEngine.terminusMatches("Ivry", "IVRY")).isTrue();
        assertThat(PositionEngine.terminusMatches("Mairie d'Ivry", "Ivry")).isTrue();
        assertThat(PositionEngine.terminusMatches("Ivry", "Mairie d'Ivry")).isTrue();
        assertThat(PositionEngine.terminusMatches("Villejuif", "Ivry")).isFalse();
        assertThat(PositionEngine.terminusMatches(null, "Ivry")).isFalse();
        assertThat(PositionEngine.terminusMatches("Ivry", null)).isFalse();
    }

    @Test
    void extractsTheLastDigitGroupAsStopKey() {
        assertThat(PositionEngine.stopKey("STIF:StopPoint:Q:463221:")).isEqualTo("463221");
        assertThat(PositionEngine.stopKey("IDFM:463221")).isEqualTo("463221");
        assertThat(PositionEngine.stopKey("IDFM:StopPoint:59:463221")).isEqualTo("463221");
        assertThat(PositionEngine.stopKey(null)).isEmpty();
        assertThat(PositionEngine.stopKey("aucun-chiffre")).isEmpty();
    }
}
