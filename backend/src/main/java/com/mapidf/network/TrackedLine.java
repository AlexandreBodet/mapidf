package com.mapidf.network;

import java.util.List;

/**
 * Une ligne suivie et ses branches : la 7 et la 13 en ont deux par sens, la 10 deux dans un
 * sens, les 13 autres lignes de métro une par sens.
 */
public record TrackedLine(String id, String gtfsRouteId, String siriLineRef,
                          String shortName, String color, String mode, List<LineBranch> branches) {
    public TrackedLine {
        branches = List.copyOf(branches);
    }
}
