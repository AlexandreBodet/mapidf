package com.mapidf.controllers.support;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;

/**
 * Compte les requêtes par clé sur une fenêtre fixe d'une minute, et refuse au-delà d'un budget.
 *
 * <p>Fenêtre fixe et non seau à jetons : à cheval sur deux fenêtres, un client peut passer deux
 * fois le budget en deux secondes. C'est assumé — ce quota arrête une boucle emballée, il
 * n'arbitre pas entre usagers, et la précision au franchissement n'a aucune valeur pour ça.
 */
@Slf4j
public final class RateLimiter {

    /** Ce qu'une vérification apprend à l'appelant. {@code retryAfterSeconds} est toujours
     *  renseigné, y compris sur une décision favorable. */
    public record Decision(boolean allowed, long retryAfterSeconds) {
    }

    private record Entry(Instant window, AtomicLong count) {
    }

    private final Clock clock;
    private final int budget;
    private final Counter rejected;
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    /** Dernière fenêtre balayée. Non volatile : deux balayages concurrents sont sans effet de
     *  bord — le modèle mémoire ne garantit pas qu'un balayage manqué soit rattrapé, mais le
     *  prédicat de {@code removeIf} porte sur la fenêtre propre à chaque entrée, donc il
     *  n'évince jamais une entrée vivante. */
    private Instant sweptWindow = Instant.EPOCH;

    public RateLimiter(Clock clock, int budget, MeterRegistry meters) {
        this.clock = clock;
        this.budget = budget;
        this.rejected = meters.counter("mapidf.ratelimit.rejected");
    }

    public Decision check(String key) {
        Instant now = clock.instant();
        Instant window = now.truncatedTo(ChronoUnit.MINUTES);
        long retryAfterSeconds =
            window.plus(1, ChronoUnit.MINUTES).getEpochSecond() - now.getEpochSecond();

        // Balayage au changement de fenêtre, et non à chaque nouvelle clé : la clé est choisie
        // par l'appelant, donc un balayage par clé neuve serait quadratique en nombre d'IP vues.
        if (!sweptWindow.equals(window)) {
            sweptWindow = window;
            entries.values().removeIf(entry -> !entry.window().equals(window));
        }

        // compute ne tient le verrou du bin que le temps d'installer une entrée neuve — aucun
        // calcul dedans, contrairement au cas que ResponseCache a écarté.
        Entry entry = entries.compute(key, (ignored, current) ->
            current != null && current.window().equals(window)
                ? current
                : new Entry(window, new AtomicLong()));

        long count = entry.count().incrementAndGet();
        if (count <= budget) {
            return new Decision(true, retryAfterSeconds);
        }

        rejected.increment();
        // Une seule ligne par clé et par fenêtre, au franchissement exact : bornée en pratique,
        // pas garantie par construction — un thread retardataire peut réinstaller une entrée
        // d'une fenêtre déjà balayée et remettre son compteur à zéro. Une métrique ne sert à
        // rien tant qu'il faut penser à la lire (même intention que LineCoverageGuard).
        if (count == budget + 1L) {
            log.warn("Quota dépassé pour {} : plus de {} requêtes sur la minute {}",
                key, budget, window);
        }
        return new Decision(false, retryAfterSeconds);
    }

    /** Clés retenues — visible pour le test d'éviction, qui doit constater le balayage. */
    int trackedKeys() {
        return entries.size();
    }
}
