package com.mapidf.network;

import java.util.List;

/**
 * Une station physique, dédoublonnée depuis ses quais. Mesuré sur le métro : 781 quais →
 * 321 stations, dont 61 correspondances (jusqu'à 5 lignes à République et Châtelet).
 */
public record Station(String id, String name, double lat, double lng,
                      List<String> platformIds, List<String> lineIds) {
    public Station {
        platformIds = List.copyOf(platformIds);
        lineIds = List.copyOf(lineIds);
    }
}
