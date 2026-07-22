package com.mapidf.rt;

import java.time.Instant;
import java.util.List;

public record RtSnapshot(Instant asOf, List<LiveJourney> journeys) {

    public record LiveJourney(String journeyRef, String directionRef, String destination,
                              String nextStopRef, Instant expectedTime) {
    }

    public static RtSnapshot empty() {
        return new RtSnapshot(Instant.EPOCH, List.of());
    }
}
