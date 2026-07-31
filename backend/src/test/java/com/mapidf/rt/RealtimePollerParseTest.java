package com.mapidf.rt;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;

import com.mapidf.configurations.properties.PrimProperties;
import com.mapidf.network.LineRegistry;
import com.mapidf.network.NetworkSnapshot;
import com.mapidf.network.TrackedLine;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class RealtimePollerParseTest {

    private static final String LINE_NINE = "STIF:Line::C01379:";
    private static final String LINE_ONE = "STIF:Line::C01371:";

    private static RealtimePoller poller() {
        LineRegistry registry = new LineRegistry();
        registry.publish(NetworkSnapshot.of(List.of(
            new TrackedLine(LINE_NINE, LINE_NINE, LINE_NINE, "9", "#000000", "METRO", List.of())),
            List.of()));
        return new RealtimePoller(
            new PrimProperties("", "apikey", "", "http://rt", Duration.ofSeconds(10), "",
                Duration.ofMinutes(5)),
            new ObjectMapper(), registry);
    }

    @Test
    void indexesEstimatedTimetableByLine() {
        RtSnapshot snapshot = RealtimePoller.parse(
            new ObjectMapper(), RtFixtures.stream(RtFixtures.siriMultiLineSample()),
            Instant.parse("2026-07-22T14:00:00Z"), Set.of(LINE_NINE, LINE_ONE));

        List<RtSnapshot.LiveJourney> nine = snapshot.forLine(LINE_NINE);
        assertThat(nine).hasSize(1);
        RtSnapshot.LiveJourney journey = nine.getFirst();
        assertThat(journey.lineRef()).isEqualTo(LINE_NINE);
        assertThat(journey.journeyRef()).isEqualTo("J1");
        assertThat(journey.directionRef()).isEqualTo("0");
        assertThat(journey.destination()).isEqualTo("Gamma");
        assertThat(journey.calls()).singleElement().satisfies(call -> {
            assertThat(call.stopRef()).isEqualTo("STIF:StopPoint:Q:2:");
            assertThat(call.time()).isEqualTo(Instant.parse("2026-07-22T14:05:00Z"));
            assertThat(call.departureStatus()).isEqualTo("ON_TIME");
        });

        assertThat(snapshot.forLine(LINE_ONE)).extracting(RtSnapshot.LiveJourney::journeyRef)
            .containsExactly("J2");
        assertThat(snapshot.forLine("STIF:Line::UNKNOWN:")).isEmpty();
    }

    @Test
    void parsesAllCallsOfAJourneyPreservingFeedOrder() {
        // parse ne trie pas et ne filtre pas : il garde tous les appels tels quels (le choix de
        // l'arrêt imminent revient à PositionEngine). Le tableau EstimatedCall est non trié.
        RtSnapshot snapshot = RealtimePoller.parse(
            new ObjectMapper(), RtFixtures.stream(RtFixtures.siriUnorderedCallsSample()),
            Instant.parse("2026-07-22T14:00:00Z"), Set.of(LINE_NINE));

        RtSnapshot.LiveJourney journey = snapshot.forLine(LINE_NINE).getFirst();
        assertThat(journey.calls()).extracting(RtSnapshot.LiveJourney.Call::stopRef)
            .containsExactly("STIF:StopPoint:Q:5:", "STIF:StopPoint:Q:2:",
                "STIF:StopPoint:Q:1:", "STIF:StopPoint:Q:8:");
    }

    @Test
    void serviceWindowWrapsAroundMidnight() {
        assertThat(RealtimePoller.inServiceHours(LocalTime.of(12, 0))).isTrue();  // plein service
        assertThat(RealtimePoller.inServiceHours(LocalTime.of(1, 0))).isTrue();   // après minuit
        assertThat(RealtimePoller.inServiceHours(LocalTime.of(5, 45))).isTrue();  // juste après ouverture
        assertThat(RealtimePoller.inServiceHours(LocalTime.of(3, 0))).isFalse();  // nuit
        assertThat(RealtimePoller.inServiceHours(LocalTime.of(5, 0))).isFalse();  // avant ouverture
    }

    @Test
    void serviceWindowCoversTheTailOfServiceAfterOneOClock() {
        // Les derniers métros partent vers 00h45 en semaine, ~01h45 les vendredis et samedis, et
        // roulent jusqu'à leur terminus. Fermer à 01h30 les effaçait de la carte alors qu'ils
        // circulaient encore.
        assertThat(RealtimePoller.inServiceHours(LocalTime.of(1, 45))).isTrue();
        assertThat(RealtimePoller.inServiceHours(LocalTime.of(2, 30))).isTrue();
        assertThat(RealtimePoller.inServiceHours(LocalTime.of(2, 59))).isTrue();
        assertThat(RealtimePoller.inServiceHours(LocalTime.of(3, 1))).isFalse();
    }

    @Test
    void forgetsTheSnapshotOnceServiceIsOver() {
        // Le snapshot survivrait à la fin du service, et PositionEngine place au dernier arrêt
        // connu quand tous les appels sont passés : la nuit, la carte montrerait ~705 courses
        // figées à leur terminus, annoncées « en circulation ».
        RealtimePoller poller = poller();
        byte[] siri = RtFixtures.siriLineNineSample();
        poller.pollOnce(url -> RtFixtures.stream(siri), Instant.ofEpochSecond(100));
        assertThat(poller.current().byLine()).isNotEmpty();

        poller.tick(LocalTime.of(4, 0), Instant.ofEpochSecond(200));

        assertThat(poller.current().byLine()).isEmpty();
        assertThat(poller.current().dataDate()).isNull();
    }

    @Test
    void skipsMalformedJourneyButKeepsValidOnes() {
        String json = """
            {"Siri":{"ServiceDelivery":{"EstimatedTimetableDelivery":[{"EstimatedJourneyVersionFrame":[{
              "EstimatedVehicleJourney":[
                {"LineRef":{"value":"L"},"DirectionRef":{"value":"Aller"},
                 "DatedVehicleJourneyRef":{"value":"BON"},"DestinationName":[{"value":"Terminus"}],
                 "EstimatedCalls":{"EstimatedCall":[
                   {"StopPointRef":{"value":"STIF:StopPoint:Q:111:"},"ExpectedArrivalTime":"2026-07-28T09:00:00.000Z","DepartureStatus":"ON_TIME"}]}},
                {"LineRef":{"value":"L"},"DirectionRef":{"value":"Aller"},
                 "DatedVehicleJourneyRef":{"value":"POURRI"},"DestinationName":[{"value":"Terminus"}],
                 "EstimatedCalls":{"EstimatedCall":[
                   {"StopPointRef":{"value":"STIF:StopPoint:Q:222:"},"ExpectedArrivalTime":"pas-une-date","DepartureStatus":"ON_TIME"}]}}
              ]}]}]}}}
            """;
        RtSnapshot snapshot = RealtimePoller.parse(new ObjectMapper(),
            RtFixtures.stream(json.getBytes(StandardCharsets.UTF_8)), Instant.now(), Set.of("L"));
        List<RtSnapshot.LiveJourney> journeys = snapshot.forLine("L");
        assertThat(journeys).hasSize(1);
        assertThat(journeys.getFirst().journeyRef()).isEqualTo("BON");
    }

    @Test
    void fallsBackToCompositeJourneyRefWhenDatedRefMissing() {
        String json = """
            {"Siri":{"ServiceDelivery":{"EstimatedTimetableDelivery":[{"EstimatedJourneyVersionFrame":[{
              "EstimatedVehicleJourney":[
                {"LineRef":{"value":"L"},"DirectionRef":{"value":"Aller"},"DestinationName":[{"value":"A"}],
                 "EstimatedCalls":{"EstimatedCall":[
                   {"StopPointRef":{"value":"STIF:StopPoint:Q:111:"},"ExpectedArrivalTime":"2026-07-28T09:00:00.000Z"}]}},
                {"LineRef":{"value":"L"},"DirectionRef":{"value":"Retour"},"DestinationName":[{"value":"B"}],
                 "EstimatedCalls":{"EstimatedCall":[
                   {"StopPointRef":{"value":"STIF:StopPoint:Q:111:"},"ExpectedArrivalTime":"2026-07-28T09:00:00.000Z"}]}}
              ]}]}]}}}
            """;
        RtSnapshot snapshot = RealtimePoller.parse(new ObjectMapper(),
            RtFixtures.stream(json.getBytes(StandardCharsets.UTF_8)), Instant.now(), Set.of("L"));
        List<RtSnapshot.LiveJourney> journeys = snapshot.forLine("L");
        assertThat(journeys).hasSize(2);
        assertThat(journeys.get(0).journeyRef()).isNotEqualTo(journeys.get(1).journeyRef());
    }

    @Test
    void keepsOnlyTheTrackedLinesOfTheGlobalFeed() {
        // Le flux global couvre 1 013 lignes pour 12 018 courses ; on n'en matérialise que
        // celles du périmètre, au fil de l'eau.
        RtSnapshot snapshot = RealtimePoller.parse(
            new ObjectMapper(), RtFixtures.stream(RtFixtures.siriMultiLineSample()),
            Instant.parse("2026-07-22T14:00:00Z"), Set.of(LINE_NINE));

        assertThat(snapshot.byLine()).containsOnlyKeys(LINE_NINE);
        assertThat(snapshot.forLine(LINE_ONE)).isEmpty();
    }

    @Test
    void keepsSeveralLinesWhenSeveralAreTracked() {
        RtSnapshot snapshot = RealtimePoller.parse(
            new ObjectMapper(), RtFixtures.stream(RtFixtures.siriMultiLineSample()),
            Instant.parse("2026-07-22T14:00:00Z"), Set.of(LINE_NINE, LINE_ONE));

        assertThat(snapshot.byLine()).containsOnlyKeys(LINE_NINE, LINE_ONE);
        assertThat(snapshot.forLine(LINE_ONE)).singleElement()
            .extracting(RtSnapshot.LiveJourney::destination).isEqualTo("Delta");
    }

    @Test
    void keepsNothingWhenNoLineIsTrackedYet() {
        // Registry pas encore réhydraté : on ne sait pas quoi suivre, donc on ne matérialise
        // rien plutôt que d'ingérer les 12 018 courses du réseau. Le poll suivant (≤60 s)
        // reprendra avec un registry rempli.
        RtSnapshot snapshot = RealtimePoller.parse(
            new ObjectMapper(), RtFixtures.stream(RtFixtures.siriMultiLineSample()),
            Instant.parse("2026-07-22T14:00:00Z"), Set.of());

        assertThat(snapshot.byLine()).isEmpty();
    }

    @Test
    void readsTheRecordedAtTimeOfEachJourney() {
        RtSnapshot snapshot = RealtimePoller.parse(
            new ObjectMapper(), RtFixtures.stream(RtFixtures.siriStaleJourneySample()),
            Instant.parse("2026-07-22T14:00:00Z"), Set.of(LINE_NINE));

        assertThat(snapshot.forLine(LINE_NINE)).singleElement()
            .extracting(RtSnapshot.LiveJourney::recordedAt)
            .isEqualTo(Instant.parse("2026-07-22T13:51:00Z"));
    }

    @Test
    void toleratesAJourneyWithoutRecordedAtTime() {
        // Les fixtures historiques n'en portent pas : l'absence ne doit pas perdre la course.
        RtSnapshot snapshot = RealtimePoller.parse(
            new ObjectMapper(), RtFixtures.stream(RtFixtures.siriMultiLineSample()),
            Instant.parse("2026-07-22T14:00:00Z"), Set.of(LINE_NINE));

        assertThat(snapshot.forLine(LINE_NINE)).singleElement()
            .extracting(RtSnapshot.LiveJourney::recordedAt).isNull();
    }
}
