package com.mapidf.rt;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class RealtimePollerParseTest {

    @Test
    void indexesEstimatedTimetableByLine() {
        RtSnapshot snapshot = RealtimePoller.parse(
            new ObjectMapper(), RtFixtures.siriMultiLineSample(), Instant.parse("2026-07-22T14:00:00Z"));

        List<RtSnapshot.LiveJourney> nine = snapshot.forLine("STIF:Line::C01379:");
        assertThat(nine).hasSize(1);
        RtSnapshot.LiveJourney journey = nine.getFirst();
        assertThat(journey.lineRef()).isEqualTo("STIF:Line::C01379:");
        assertThat(journey.journeyRef()).isEqualTo("J1");
        assertThat(journey.directionRef()).isEqualTo("0");
        assertThat(journey.destination()).isEqualTo("Gamma");
        assertThat(journey.calls()).singleElement().satisfies(call -> {
            assertThat(call.stopRef()).isEqualTo("STIF:StopPoint:Q:2:");
            assertThat(call.time()).isEqualTo(Instant.parse("2026-07-22T14:05:00Z"));
            assertThat(call.departureStatus()).isEqualTo("ON_TIME");
        });

        assertThat(snapshot.forLine("STIF:Line::C01371:")).extracting(RtSnapshot.LiveJourney::journeyRef)
            .containsExactly("J2");
        assertThat(snapshot.forLine("STIF:Line::UNKNOWN:")).isEmpty();
    }

    @Test
    void parsesAllCallsOfAJourneyPreservingFeedOrder() {
        // parse ne trie pas et ne filtre pas : il garde tous les appels tels quels (le choix de
        // l'arrêt imminent revient à PositionEngine). Le tableau EstimatedCall est non trié.
        RtSnapshot snapshot = RealtimePoller.parse(
            new ObjectMapper(), RtFixtures.siriUnorderedCallsSample(), Instant.parse("2026-07-22T14:00:00Z"));

        RtSnapshot.LiveJourney journey = snapshot.forLine("STIF:Line::C01379:").getFirst();
        assertThat(journey.calls()).extracting(RtSnapshot.LiveJourney.Call::stopRef)
            .containsExactly("STIF:StopPoint:Q:5:", "STIF:StopPoint:Q:2:",
                "STIF:StopPoint:Q:1:", "STIF:StopPoint:Q:8:");
    }

    @Test
    void serviceWindowWrapsAroundMidnight() {
        assertThat(RealtimePoller.inServiceHours(LocalTime.of(12, 0))).isTrue();  // plein service
        assertThat(RealtimePoller.inServiceHours(LocalTime.of(1, 0))).isTrue();   // après minuit, avant 01h30
        assertThat(RealtimePoller.inServiceHours(LocalTime.of(5, 45))).isTrue();  // juste après ouverture
        assertThat(RealtimePoller.inServiceHours(LocalTime.of(3, 0))).isFalse();  // nuit
        assertThat(RealtimePoller.inServiceHours(LocalTime.of(5, 0))).isFalse();  // avant ouverture
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
        RtSnapshot snapshot = RealtimePoller.parse(new ObjectMapper(), json.getBytes(StandardCharsets.UTF_8), Instant.now());
        List<RtSnapshot.LiveJourney> journeys = snapshot.forLine("L");
        assertThat(journeys).hasSize(1);
        assertThat(journeys.getFirst().journeyRef()).isEqualTo("BON");
    }
}
