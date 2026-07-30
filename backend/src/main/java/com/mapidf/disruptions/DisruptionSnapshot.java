package com.mapidf.disruptions;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Instantané des perturbations, indexé par ligne suivie et par arrêt. Immuable et thread-safe,
 * comme {@link com.mapidf.rt.RtSnapshot}.
 *
 * <p>Le filtre « en cours » n'est PAS appliqué à l'ingestion : l'instantané garde les périodes
 * et c'est la lecture qui tranche, puisque la réponse dépend de l'instant de la requête.
 */
public record DisruptionSnapshot(Instant asOf, Map<String, List<Disruption>> byLine,
                                 Map<String, List<Disruption>> byStop) {

    public DisruptionSnapshot {
        byLine = deepCopy(byLine);
        byStop = deepCopy(byStop);
    }

    private static Map<String, List<Disruption>> deepCopy(Map<String, List<Disruption>> source) {
        Map<String, List<Disruption>> copy = new HashMap<>();
        source.forEach((key, values) -> copy.put(key, List.copyOf(values)));
        return Map.copyOf(copy);
    }

    /** Perturbations en cours sur une ligne, la plus grave d'abord. */
    public List<Disruption> forLine(String lineId, Instant instant) {
        return active(byLine.getOrDefault(lineId, List.of()), instant);
    }

    /** Perturbations en cours sur un arrêt, clé normalisée par {@code PositionEngine.stopKey}. */
    public List<Disruption> forStop(String stopKey, Instant instant) {
        return active(byStop.getOrDefault(stopKey, List.of()), instant);
    }

    private static List<Disruption> active(List<Disruption> candidates, Instant instant) {
        return candidates.stream()
            .filter(disruption -> disruption.activeAt(instant))
            .sorted(Comparator.comparing(Disruption::severity))
            .toList();
    }

    public static DisruptionSnapshot empty() {
        return new DisruptionSnapshot(Instant.EPOCH, Map.of(), Map.of());
    }
}
