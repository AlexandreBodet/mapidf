package com.mapidf.network;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;

/**
 * Recherche de station par sous-chaîne, insensible à la casse et aux accents (UX-5a). Scanne le
 * registre déjà en mémoire — 321 stations aujourd'hui — plutôt qu'une requête DB : voir la spec
 * pour la justification chiffrée, y compris après un élargissement à RER/Transilien.
 */
public final class StationSearch {

    private StationSearch() {
    }

    public static List<Station> search(List<Station> stations, String query, int limit) {
        String needle = normalize(query);
        if (needle.isBlank()) {
            return List.of();
        }
        return stations.stream()
            .filter(station -> station.normalizedName().contains(needle))
            .limit(limit)
            .toList();
    }

    static String normalize(String s) {
        String stripped = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return stripped.toLowerCase(Locale.FRENCH);
    }
}
