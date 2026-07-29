package com.mapidf.gtfs;

import com.mapidf.data.enums.TransportMode;

/**
 * Une ligne suivie, telle que décrite par {@code routes.txt}. Tout est dérivé du GTFS :
 * aucune saisie manuelle par ligne (vérifié le 2026-07-29 sur les 16 lignes de métro).
 *
 * @param gtfsRouteId  {@code route_id} GTFS ("IDFM:C01379")
 * @param siriLineRef  LineRef du flux temps réel, dérivé ("STIF:Line::C01379:")
 * @param shortName    nom court d'affichage, non normalisé ("3B")
 * @param color        couleur CSS, préfixée '#'
 */
public record LineDescriptor(String gtfsRouteId, String siriLineRef,
                             String shortName, String color, TransportMode mode) {

    public static LineDescriptor of(String gtfsRouteId, String shortName, String color, TransportMode mode) {
        return new LineDescriptor(
            gtfsRouteId,
            siriLineRef(gtfsRouteId),
            shortName == null ? "" : shortName.trim(),
            cssColor(color),
            mode);
    }

    /**
     * Identifiant public d'une ligne, dérivé du {@code route_short_name} : ("3B" → "3b").
     *
     * <p>C'est la clé de jointure entre {@code /network}, {@code /vehicles},
     * {@code /stations/{id}/departures}, le filtre client et les compteurs du sélecteur. Le
     * descripteur ne la porte PAS en champ : {@code route.short_name} est persisté brut, et
     * {@code NetworkRegistryBuilder} — seul producteur des identifiants que voit l'API —
     * appelle cette méthode au build du registry. Une seconde implémentation de la règle
     * ailleurs serait une divergence programmée, la copie testée n'étant pas forcément
     * l'active (constat de revue globale I2).
     */
    public static String publicId(String shortName) {
        return shortName == null ? ""
            : shortName.trim().toLowerCase(java.util.Locale.ROOT).replace(" ", "");
    }

    // Le code de ligne est le DERNIER segment du route_id ("IDFM:C01379" → "C01379") : c'est
    // lui qui apparaît dans le LineRef SIRI. Un route_id à segments supplémentaires reste géré.
    private static String siriLineRef(String gtfsRouteId) {
        String raw = gtfsRouteId == null ? "" : gtfsRouteId;
        int lastColon = raw.lastIndexOf(':');
        String code = lastColon < 0 ? raw : raw.substring(lastColon + 1);
        return "STIF:Line::" + code + ":";
    }

    // route_color GTFS est un hex SANS '#' (ex. "D2D200") ; sans le préfixe, MapLibre rejette
    // la couche et le tracé n'apparaît pas.
    private static String cssColor(String gtfsColor) {
        if (gtfsColor == null || gtfsColor.isBlank()) {
            return "#000000";
        }
        String trimmed = gtfsColor.trim();
        return trimmed.startsWith("#") ? trimmed : "#" + trimmed;
    }
}
