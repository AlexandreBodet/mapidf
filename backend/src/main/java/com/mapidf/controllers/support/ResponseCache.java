package com.mapidf.controllers.support;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Mémorise la réponse d'un endpoint et la réutilise tant que ses instantanés source sont les
 * MÊMES INSTANCES et que l'horloge n'a pas changé de seconde.
 *
 * <p>L'invalidation par identité, et non par TTL, est ce qui décide : un poll publie toujours un
 * instantané neuf, donc une donnée fraîche est servie sans attendre l'expiration. Un TTL pur
 * retarderait un poll d'une seconde — et casserait les IT de contrôleur, qui pollent puis
 * interrogent dans la même seconde.
 *
 * <p>La comparaison structurelle serait pourtant possible (les instantanés sont des records),
 * mais elle parcourrait des maps de ~705 courses à chaque requête. Et le sens de l'erreur de
 * l'identité est le bon : deux instances égales mais distinctes coûtent un recalcul inutile,
 * jamais une réponse périmée.
 */
public final class ResponseCache<K, V> {

    /** Clé interne de la surcharge sans clé, pour n'avoir qu'un seul chemin de code. */
    private static final Object SINGLETON = new Object();

    private record Entry<V>(List<Object> sources, Instant second, V value) {
    }

    private final Clock clock;
    private final Counter hits;
    private final Counter misses;
    private final Map<Object, Entry<V>> entries = new ConcurrentHashMap<>();

    public ResponseCache(Clock clock, String name, MeterRegistry meters) {
        this.clock = clock;
        this.hits = meters.counter("mapidf.cache.hits", "cache", name);
        this.misses = meters.counter("mapidf.cache.misses", "cache", name);
    }

    /** Entrée unique — pour un endpoint sans paramètre. */
    public V get(List<Object> sources, Function<Instant, V> compute) {
        return lookup(SINGLETON, sources, compute);
    }

    /** Une entrée par clé. */
    public V get(K key, List<Object> sources, Function<Instant, V> compute) {
        return lookup(key, sources, compute);
    }

    private V lookup(Object key, List<Object> sources, Function<Instant, V> compute) {
        // Un seul appel à l'horloge : l'instant remis au calcul et la seconde qui indexe
        // l'entrée doivent venir du même relevé, sinon un calcul peut s'enregistrer sous la
        // seconde précédente.
        Instant now = clock.instant();
        Instant second = now.truncatedTo(ChronoUnit.SECONDS);

        Entry<V> cached = entries.get(key);
        if (cached != null && cached.second().equals(second)
            && sameInstances(cached.sources(), sources)) {
            hits.increment();
            return cached.value();
        }

        // Deux requêtes concurrentes sur une entrée absente calculent toutes les deux : c'est
        // délibéré. ConcurrentHashMap.compute tiendrait le verrou du bin pendant tout le calcul,
        // échangeant un doublon rare contre une contention certaine. Les deux calculs rendent une
        // valeur équivalente — le doublon coûte du CPU, jamais de la justesse.
        misses.increment();
        V value = compute.apply(now);
        entries.put(key, new Entry<>(List.copyOf(sources), second, value));
        return value;
    }

    private static boolean sameInstances(List<Object> cached, List<Object> current) {
        if (cached.size() != current.size()) {
            return false;
        }
        for (int i = 0; i < cached.size(); i++) {
            if (cached.get(i) != current.get(i)) {
                return false;
            }
        }
        return true;
    }
}
