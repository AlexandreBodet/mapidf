package com.mapidf.controllers.stations;

import java.time.Instant;
import java.util.List;

/**
 * Prochains passages à une station. Déplacé ici depuis le paquet {@code controllers.lines}
 * supprimé avec l'API des lignes : la tâche 12 rebranche le contrôleur
 * {@code /stations/{id}/departures} sur ce paquet et y ajoute le groupement par ligne.
 */
public record DeparturesResponse(String stationName, List<Direction> directions) {

    public record Direction(String destination, List<Passage> passages) {
    }

    public record Passage(String journeyRef, Instant expectedTime, String status) {
    }
}
