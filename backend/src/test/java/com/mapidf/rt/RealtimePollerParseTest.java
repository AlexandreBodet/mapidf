package com.mapidf.rt;

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
        assertThat(journey.nextStopRef()).isEqualTo("STIF:StopPoint:Q:2:");
        assertThat(journey.expectedTime()).isEqualTo(Instant.parse("2026-07-22T14:05:00Z"));
        assertThat(journey.departureStatus()).isEqualTo("ON_TIME");

        assertThat(snapshot.forLine("STIF:Line::C01371:")).extracting(RtSnapshot.LiveJourney::journeyRef)
            .containsExactly("J2");
        assertThat(snapshot.forLine("STIF:Line::UNKNOWN:")).isEmpty();
    }

    @Test
    void serviceWindowWrapsAroundMidnight() {
        assertThat(RealtimePoller.inServiceHours(LocalTime.of(12, 0))).isTrue();  // plein service
        assertThat(RealtimePoller.inServiceHours(LocalTime.of(1, 0))).isTrue();   // après minuit, avant 01h30
        assertThat(RealtimePoller.inServiceHours(LocalTime.of(5, 45))).isTrue();  // juste après ouverture
        assertThat(RealtimePoller.inServiceHours(LocalTime.of(3, 0))).isFalse();  // nuit
        assertThat(RealtimePoller.inServiceHours(LocalTime.of(5, 0))).isFalse();  // avant ouverture
    }
}
