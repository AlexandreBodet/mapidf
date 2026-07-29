package com.mapidf.position;

import java.time.Instant;

/**
 * Un véhicule placé sur la carte.
 *
 * @param recordedAt  dernière mise à jour de la course côté SIRI, affichée telle quelle
 * @param confidence  fiabilité du PLACEMENT, sur un signal structurel : une course à un seul
 *                    appel est bornée à l'arrêt précédant celui-ci (36 % du flux métro mesuré).
 *                    Aucune ETA n'intervient — un train perturbé ne doit jamais être masqué.
 */
public record Vehicle(String journeyRef, String lineId, double lat, double lng, double bearing,
                      String status, String headsign, String nextStop, Instant expectedTime,
                      Instant recordedAt, Confidence confidence) {

    public enum Confidence {
        RELIABLE, APPROXIMATE
    }
}
