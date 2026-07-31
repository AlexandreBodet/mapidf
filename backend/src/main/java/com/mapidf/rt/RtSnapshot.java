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

    /**
     * Une course temps réel = son identité + la liste de ses arrêts estimés (dans l'ordre du
     * flux, PAS trié).
     *
     * <p>{@code recordedAt} est l'horodatage de dernière mise à jour de la course, présent sur
     * les 705 courses métro mesurées le 2026-07-29 (médiane 0,4 min, max 16,8 min d'âge).
     * Information affichée telle quelle : ce n'est PAS un critère d'atténuation — mesuré sur
     * une ligne 8 en perturbation, c'était la ligne à la donnée la plus fraîche du réseau.
     */
    public record LiveJourney(String lineRef, String journeyRef, String directionRef,
                              String destination, Instant recordedAt, List<Call> calls) {

        public LiveJourney {
            calls = List.copyOf(calls);
        }

        /** Un passage estimé à un arrêt : sa référence, son heure estimée, son statut. */
        public record Call(String stopRef, Instant time, String departureStatus) {
        }
    }

    /** Courses temps réel d'une ligne (liste vide si la ligne est absente du flux). */
    public List<LiveJourney> forLine(String lineRef) {
        return byLine.getOrDefault(lineRef, List.of());
    }

    /**
     * Date de la donnée, ou {@code null} tant qu'aucun poll n'a abouti — l'{@code EPOCH} de
     * {@link #empty()} n'est pas une date de mise à jour et ne doit jamais s'afficher comme telle.
     */
    public Instant dataDate() {
        return Instant.EPOCH.equals(asOf) ? null : asOf;
    }

    public static RtSnapshot empty() {
        return new RtSnapshot(Instant.EPOCH, Map.of());
    }
}
