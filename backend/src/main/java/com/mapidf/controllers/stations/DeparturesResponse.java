package com.mapidf.controllers.stations;

import java.time.Instant;
import java.util.List;

import com.mapidf.controllers.disruptions.DisruptionsResponse;

/**
 * Prochains passages à une station, groupés par ligne puis par direction.
 *
 * <p>Mesuré : 61 stations sur 321 sont des correspondances, jusqu'à 5 lignes. Grouper par
 * destination seule à travers plusieurs lignes fusionnerait deux lignes partageant un nom de
 * destination — d'où le niveau « ligne ».
 *
 * <p>Sur une ligne à branches, une station du tronc commun affiche plus de deux directions
 * (la 13 à Saint-Lazare montre Asnières et Saint-Denis séparément) : c'est le comportement juste.
 *
 * <p>{@code disruptions} ne porte que les perturbations visant les QUAIS de cette station — c'est
 * ce que l'anneau sur la carte a promis d'expliquer. Les perturbations de ligne entière sont dans
 * le sélecteur de lignes : les répéter à chacune des stations d'une ligne coupée noierait les
 * correspondances (jusqu'à 5 lignes). Le type est celui de {@code /disruptions}, même charge
 * utile — inutile d'en inventer un jumeau.
 */
public record DeparturesResponse(String stationName, List<LineDepartures> lines,
                                 List<DisruptionsResponse.Item> disruptions) {

    public record LineDepartures(String lineId, String shortName, String color,
                                 List<Direction> directions) {
    }

    public record Direction(String destination, List<Passage> passages) {
    }

    public record Passage(String journeyRef, Instant expectedTime, String status) {
    }
}
