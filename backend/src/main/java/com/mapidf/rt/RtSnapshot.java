package com.mapidf.rt;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Instantané temps réel de TOUT le réseau (flux SIRI global {@code estimated-timetable}),
 * indexé par ligne ({@code LineRef} SIRI). Immuable et thread-safe.
 */
public record RtSnapshot(Instant asOf, Map<String, List<LiveJourney>> byLine) {

    public RtSnapshot {
        Map<String, List<LiveJourney>> copy = new HashMap<>();
        byLine.forEach((line, journeys) -> copy.put(line, List.copyOf(journeys)));
        byLine = Map.copyOf(copy);
    }

    public record LiveJourney(String lineRef, String journeyRef, String directionRef, String destination,
                              String nextStopRef, Instant expectedTime, String departureStatus) {
    }

    /** Courses temps réel d'une ligne (liste vide si la ligne est absente du flux). */
    public List<LiveJourney> forLine(String lineRef) {
        return byLine.getOrDefault(lineRef, List.of());
    }

    public static RtSnapshot empty() {
        return new RtSnapshot(Instant.EPOCH, Map.of());
    }
}
