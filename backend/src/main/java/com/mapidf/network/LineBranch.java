package com.mapidf.network;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.mapidf.position.StopOnLine;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.linearref.LengthIndexedLine;

/**
 * Une branche prête à servir : sa géométrie déjà indexée et ses arrêts déjà projetés.
 *
 * <p>{@code indexByStopKey} rend la recherche d'un arrêt en O(1). Sans elle, le choix de
 * branche coûterait ~100 000 comparaisons de chaînes par requête (705 courses × jusqu'à
 * 4 branches candidates × ~35 arrêts).
 *
 * <p>{@link LengthIndexedLine} ne porte que la géométrie et n'expose que des lectures : il est
 * partagé sans copie entre toutes les requêtes.
 *
 * <p><b>Construire exclusivement via {@link #of}</b> : le constructeur canonique n'est utilisé
 * qu'en interne par la fabrique, qui garantit {@code indexed} cohérent avec {@code geom} et
 * {@code indexByStopKey} cohérent avec {@code stops}. L'appeler directement permettrait de
 * publier une branche où ces invariants ne tiennent plus.
 */
public record LineBranch(String shapeId, short direction, String terminusName,
                         LineString geom, LengthIndexedLine indexed,
                         List<StopOnLine> stops, Map<String, Integer> indexByStopKey) {

    public static LineBranch of(String shapeId, short direction, String terminusName,
                                LineString geom, List<StopOnLine> stops) {
        List<StopOnLine> orderedStops = List.copyOf(stops);
        Map<String, Integer> index = new HashMap<>();
        for (int i = 0; i < orderedStops.size(); i++) {
            index.putIfAbsent(orderedStops.get(i).stopKey(), i);
        }
        return new LineBranch(shapeId, direction, terminusName, geom,
            new LengthIndexedLine(geom), orderedStops, Map.copyOf(index));
    }

    /** Rang de l'arrêt dans cette branche, ou −1 s'il n'y figure pas (y compris pour {@code null}). */
    public int indexOf(String stopKey) {
        return stopKey == null ? -1 : indexByStopKey.getOrDefault(stopKey, -1);
    }
}
