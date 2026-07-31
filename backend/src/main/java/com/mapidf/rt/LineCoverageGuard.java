package com.mapidf.rt;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.mapidf.network.LineRegistry;
import com.mapidf.network.TrackedLine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Rend audible le garde-fou que les métriques par ligne rendaient seulement mesurable : une
 * ligne suivie qui n'a plus aucune course pendant un quart d'heure est signalée dans les logs.
 *
 * <p>C'est le risque n°1 de la spec multi-lignes : si IDFM change le code d'une ligne, la
 * dérivation de son {@code LineRef} échoue et la ligne tombe à zéro train sans que rien ne
 * casse. La carte reste belle, il manque juste une ligne — invisible sans aller lire
 * {@code /actuator/metrics} en y pensant.
 */
@Slf4j
@Component
public class LineCoverageGuard {

    private static final ZoneId PARIS = ZoneId.of("Europe/Paris");

    /**
     * 15 min : très au-delà de l'intervalle entre deux métros (2 à 8 min) et de deux polls
     * manqués. Un zéro qui dure aussi longtemps est structurel, pas un creux de trafic. Constante
     * et non configurable : la spec écarte tout seuil par ligne, ce n'est pas un réglage mais un
     * ordre de grandeur.
     */
    static final Duration ZERO_TOLERANCE = Duration.ofMinutes(15);

    private final LineRegistry registry;
    private final RealtimePoller poller;
    private final Map<String, Instant> emptySince = new ConcurrentHashMap<>();
    private final Set<String> reported = ConcurrentHashMap.newKeySet();

    public LineCoverageGuard(LineRegistry registry, RealtimePoller poller) {
        this.registry = registry;
        this.poller = poller;
    }

    @Scheduled(fixedDelay = 60_000)
    public void run() {
        check(Instant.now());
    }

    /**
     * @return les lignes signalées à cet appel — vide la plupart du temps. Valeur de retour
     *     plutôt qu'un état interne à inspecter : c'est ce qui rend la règle testable sans
     *     capturer les logs.
     */
    List<String> check(Instant now) {
        if (!RealtimePoller.inServiceHours(LocalTime.ofInstant(now, PARIS))) {
            // La nuit, le poller ne tourne pas : toutes les lignes sont à zéro par construction.
            forget();
            return List.of();
        }
        List<TrackedLine> lines = registry.current().lines();
        if (lines.isEmpty()) {
            return List.of();
        }
        RtSnapshot snapshot = poller.current();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (TrackedLine line : lines) {
            counts.put(line.id(), snapshot.forLine(line.siriLineRef()).size());
        }

        if (counts.values().stream().allMatch(count -> count == 0)) {
            // Réseau entier à zéro : c'est le flux qui est tombé, pas une ligne. Le compteur
            // mapidf.rt.poll.failures et l'âge du snapshot le disent déjà ; seize avertissements
            // identiques n'ajouteraient rien et masqueraient le vrai cas, isolé.
            forget();
            return List.of();
        }

        List<String> fresh = new ArrayList<>();
        counts.forEach((lineId, count) -> {
            if (count > 0) {
                emptySince.remove(lineId);
                if (reported.remove(lineId)) {
                    log.info("[GUARD] ligne {} : courses de nouveau présentes", lineId);
                }
                return;
            }
            Instant since = emptySince.computeIfAbsent(lineId, key -> now);
            if (Duration.between(since, now).compareTo(ZERO_TOLERANCE) >= 0 && reported.add(lineId)) {
                log.warn("[GUARD] ligne {} : aucune course dans le flux depuis {} min alors que le "
                    + "reste du réseau en a — code de ligne changé chez IDFM ? "
                    + "(remède : app.network.exclude, cf. roadmap)", lineId,
                    Duration.between(since, now).toMinutes());
                fresh.add(lineId);
            }
        });
        return fresh;
    }

    private void forget() {
        emptySince.clear();
        reported.clear();
    }
}
