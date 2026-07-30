package com.mapidf.controllers.stations;

import java.time.Instant;
import java.util.List;

/**
 * Prochains passages à une station, groupés par ligne puis par direction.
 *
 * <p>Mesuré : 61 stations sur 321 sont des correspondances, jusqu'à 5 lignes. Grouper par
 * destination seule à travers plusieurs lignes fusionnerait deux lignes partageant un nom de
 * destination — d'où le niveau « ligne ».
 *
 * <p>Sur une ligne à branches, une station du tronc commun affiche plus de deux directions
 * (la 13 à Saint-Lazare montre Asnières et Saint-Denis séparément) : c'est le comportement juste.
 */
public record DeparturesResponse(String stationName, List<LineDepartures> lines) {

    public record LineDepartures(String lineId, String shortName, String color,
                                 List<Direction> directions) {
    }

    public record Direction(String destination, List<Passage> passages) {
    }

    public record Passage(String journeyRef, Instant expectedTime, String status) {
    }
}
