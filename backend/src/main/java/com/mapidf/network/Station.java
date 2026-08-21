package com.mapidf.network;

import java.util.List;

/**
 * Une station physique, dédoublonnée depuis ses quais. Mesuré sur le métro : 781 quais →
 * 321 stations, dont 61 correspondances (jusqu'à 5 lignes à République et Châtelet).
 */
public record Station(String id, String name, double lat, double lng,
                      List<String> platformIds, List<String> lineIds, String normalizedName) {
    public Station {
        platformIds = List.copyOf(platformIds);
        lineIds = List.copyOf(lineIds);
    }

    /** Conserve la signature existante : normalizedName se déduit, il ne s'invente pas ailleurs. */
    public Station(String id, String name, double lat, double lng,
                   List<String> platformIds, List<String> lineIds) {
        this(id, name, lat, lng, platformIds, lineIds, StationSearch.normalize(name));
    }
}
