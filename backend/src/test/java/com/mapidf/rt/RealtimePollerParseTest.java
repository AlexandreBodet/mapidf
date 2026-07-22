package com.mapidf.rt;

import java.time.Instant;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class RealtimePollerParseTest {

    @Test
    void parsesEstimatedTimetable() throws Exception {
        RtSnapshot snapshot = RealtimePoller.parse(
            new ObjectMapper(), RtFixtures.siriLineNineSample(), Instant.parse("2026-07-22T14:00:00Z"));

        assertThat(snapshot.journeys()).hasSize(1);
        RtSnapshot.LiveJourney journey = snapshot.journeys().getFirst();
        assertThat(journey.journeyRef()).isEqualTo("J1");
        assertThat(journey.directionRef()).isEqualTo("0");
        assertThat(journey.destination()).isEqualTo("Gamma");
        assertThat(journey.nextStopRef()).isEqualTo("STIF:StopPoint:Q:2:");
        assertThat(journey.expectedTime()).isEqualTo(Instant.parse("2026-07-22T14:05:00Z"));
    }
}
