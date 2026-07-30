package com.mapidf.controllers.disruptions;

import java.time.Instant;
import java.util.List;

/**
 * Perturbations en cours, groupées par ligne suivie. Une ligne sans perturbation est absente.
 *
 * <p>{@code severity} est la pire des perturbations de la ligne : c'est elle qui pilote la
 * couleur d'un indicateur côté carte, sans que le front ait à trier.
 */
public record DisruptionsResponse(Instant asOf, List<LineDisruptions> lines) {

    public record LineDisruptions(String lineId, String severity, List<Item> items) {
    }

    /** Le HTML du champ {@code message} du flux n'est jamais transmis (cf. Disruption). */
    public record Item(String severity, String cause, String title, String shortMessage) {
    }
}
