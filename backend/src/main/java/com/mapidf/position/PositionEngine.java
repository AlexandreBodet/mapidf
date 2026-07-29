package com.mapidf.position;

import java.util.List;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.linearref.LengthIndexedLine;
import org.springframework.stereotype.Component;

/**
 * Placement des véhicules sur le tracé. Le calcul lui-même est neutralisé le temps de la
 * bascule vers les branches : il reposait sur {@code LineSchedule} (un tracé, N sens),
 * supprimé avec la table {@code trip}. Ne subsistent ici que les primitives réutilisées
 * telles quelles par la suite — {@link #stopKey(String)} est déjà consommée par
 * {@code StationDepartureService}. La tâche 9 réécrit le moteur sur les branches.
 */
@Component
public class PositionEngine {

    static int indexOfStop(List<StopOnLine> stops, String key) {
        for (int i = 0; i < stops.size(); i++) {
            if (stops.get(i).stopKey().equals(key)) {
                return i;
            }
        }
        return -1;
    }

    static double bearing(LengthIndexedLine indexed, double fromDistance, double toDistance) {
        Coordinate a = indexed.extractPoint(fromDistance);
        Coordinate b = indexed.extractPoint(toDistance);
        double angle = Math.toDegrees(Math.atan2(b.x - a.x, b.y - a.y));
        return (angle + 360) % 360;
    }

    static double clamp(double value, double low, double high) {
        return Math.max(low, Math.min(high, value));
    }

    private static final java.util.regex.Pattern DIGIT_GROUP = java.util.regex.Pattern.compile("\\d+");

    // On extrait le DERNIER groupe de chiffres de la référence : les ids réels (SIRI
    // "STIF:StopPoint:Q:463221:", GTFS "IDFM:463221") n'en ont qu'un, mais un id à préfixe
    // numérique ("IDFM:StopPoint:59:463221") casserait un simple strip de tous les non-chiffres.
    public static String stopKey(String rawRef) {
        if (rawRef == null) {
            return "";
        }
        java.util.regex.Matcher matcher = DIGIT_GROUP.matcher(rawRef);
        String last = "";
        while (matcher.find()) {
            last = matcher.group();
        }
        return last;
    }
}
