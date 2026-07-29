package com.mapidf.network;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * État réseau immuable, publié en bloc par {@link LineRegistry}. Les index de résolution sont
 * construits une fois à la fabrication : aucune requête n'a besoin de la base.
 *
 * <p><b>Construire exclusivement via {@link #of} ou {@link #empty}</b> : le constructeur
 * canonique n'est pas verrouillé, mais l'appeler directement permettrait de publier un
 * snapshot dont {@code linesById}/{@code linesBySiriRef}/{@code stationsById} ne correspondent
 * plus à {@code lines}/{@code stations} — la source unique de vérité doit rester cohérente
 * pour les dix tâches qui la consomment.
 */
public record NetworkSnapshot(List<TrackedLine> lines,
                              Map<String, TrackedLine> linesById,
                              Map<String, TrackedLine> linesBySiriRef,
                              List<Station> stations,
                              Map<String, Station> stationsById) {

    public static NetworkSnapshot of(List<TrackedLine> lines, List<Station> stations) {
        return new NetworkSnapshot(
            List.copyOf(lines),
            lines.stream().collect(Collectors.toUnmodifiableMap(TrackedLine::id, Function.identity())),
            lines.stream().collect(Collectors.toUnmodifiableMap(TrackedLine::siriLineRef, Function.identity())),
            List.copyOf(stations),
            stations.stream().collect(Collectors.toUnmodifiableMap(Station::id, Function.identity())));
    }

    public static NetworkSnapshot empty() {
        return of(List.of(), List.of());
    }
}
