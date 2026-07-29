package com.mapidf.network;

import java.util.List;

/** Une ligne suivie et ses branches (1 par sens pour 13 des 16 lignes, 2 pour la 7 et la 13). */
public record TrackedLine(String id, String gtfsRouteId, String siriLineRef,
                          String shortName, String color, String mode, List<LineBranch> branches) {
    public TrackedLine {
        branches = List.copyOf(branches);
    }
}
