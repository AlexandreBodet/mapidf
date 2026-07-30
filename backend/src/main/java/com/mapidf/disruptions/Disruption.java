package com.mapidf.disruptions;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * Une perturbation du flux {@code disruptions_bulk}. Le champ {@code message} du flux est
 * volontairement absent : il contient du HTML produit par un tiers, qu'on ne rendra pas.
 * {@code title} et {@code shortMessage} sont du texte, et suffisent.
 */
public record Disruption(String id, Severity severity, String cause, String title,
                         String shortMessage, List<Period> periods) {

    public Disruption {
        periods = List.copyOf(periods);
    }

    /** Ordre = gravité décroissante, pour trier et pour retenir la pire d'une ligne. */
    public enum Severity {
        BLOQUANTE, PERTURBEE, INFORMATION, INCONNUE;

        public static Severity fromFeed(String raw) {
            if (raw == null) {
                return INCONNUE;
            }
            try {
                return valueOf(raw.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                // Une valeur inédite ne doit pas faire perdre la perturbation.
                return INCONNUE;
            }
        }
    }

    public record Period(Instant begin, Instant end) {
    }

    /**
     * Mesuré le 2026-07-30 : 15 perturbations touchaient le métro, dont 4 seulement en cours —
     * les autres étaient des travaux d'août. Sans ce filtre, l'appli annoncerait une ligne
     * coupée trois semaines à l'avance.
     */
    public boolean activeAt(Instant instant) {
        return periods.stream().anyMatch(period ->
            !instant.isBefore(period.begin()) && !instant.isAfter(period.end()));
    }
}
