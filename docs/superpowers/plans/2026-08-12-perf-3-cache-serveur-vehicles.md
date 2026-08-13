# PERF-3 — Cache serveur des endpoints chauds : plan d'implémentation

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rendre le coût CPU des trois endpoints chauds constant par seconde au lieu de linéaire en nombre de requêtes, sans jamais retarder l'affichage d'une donnée fraîche.

**Architecture:** Un composant `ResponseCache<K, V>` mémorise la valeur d'une réponse et la réutilise si — et seulement si — ses instantanés source sont **les mêmes instances** (`==`) et que l'horloge est dans la même seconde. L'identité de référence, et non un TTL, est ce qui rend un poll frais visible immédiatement. Chaque contrôleur instancie le sien ; `/vehicles` y cache des **octets sérialisés**, les deux autres leur **record**.

**Tech Stack:** Java 25, Spring Boot 4.1, Lombok, Jackson 3 (`tools.jackson.databind`), Micrometer, JUnit 5 + AssertJ, MockMvc, Testcontainers.

**Spec de référence :** [2026-08-12-perf-3-cache-serveur-vehicles-design.md](../specs/2026-08-12-perf-3-cache-serveur-vehicles-design.md). Les renvois `§ N` ci-dessous y pointent.

## Global Constraints

- **`./mvnw` exige Java 25** : le `java` du shell est en 21. Toute commande Maven de ce plan se lance avec `JAVA_HOME=~/.jdks/temurin-25.0.4` en préfixe. Sans ça, le build échoue sur la version de release.
- **Jackson 3, pas Jackson 2** : `tools.jackson.databind.ObjectMapper`, jamais `com.fasterxml`. Sur un `JsonNode`, `.asString()` et non `.asText()`.
- **Deux régimes de test, à ne pas confondre.** Pour du **code neuf** (tâche 1), TDD strict : le test s'écrit et se lance *avant* l'implémentation, et l'étape « vérifier que ça échoue » n'est pas décorative — c'est elle qui prouve que le test teste quelque chose. Pour un **comportement qui existe déjà** et que le chantier risque de casser (les tests d'invalidation des tâches 2 à 4), le test est un **filet de régression** : il passe dès son écriture, et c'est normal. Le plan signale explicitement, à chaque fois, lequel des deux est attendu — un test annoncé « passe déjà » n'est pas un défaut de TDD. Quand un filet porte sur du code qu'on va modifier sans que le test doive changer, on le prouve par une **mutation de contrôle** (cf. tâche 1, étape 5).
- **Commentaires sobres** : seulement le « pourquoi » non-évident, en une ou deux lignes. Pas de paraphrase du code.
- **Records pour les DTO immuables**, Lombok pour le reste.
- **Messages de commit** en français, préfixés du type conventionnel et du chantier : `feat(perf-3): …`, `test(perf-3): …`, `docs(perf-3): …`.
- **Ne jamais démarrer ni arrêter le backend, le front ou Docker.** Ils sont pilotés par le développeur depuis son IDE. Tout ce que ce plan vérifie passe par Maven.
- **`Cache-Control: no-store` sur les trois endpoints**, exactement cette valeur (§ 4).
- **Noms de métriques**, exactement : `mapidf.cache.hits` et `mapidf.cache.misses`, tag `cache` valant `vehicles`, `disruptions` ou `departures`.

## Structure des fichiers

| Fichier | Rôle | Tâche |
|---|---|---|
| `backend/src/main/java/com/mapidf/controllers/support/ResponseCache.java` | **Créé.** Le composant, seul porteur de la règle d'invalidation. Aucune dépendance à Spring. | 1 |
| `backend/src/test/java/com/mapidf/controllers/support/ResponseCacheTest.java` | **Créé.** Les six tests unitaires, horloge avancée à la main. | 1 |
| `backend/src/main/java/com/mapidf/configurations/ClockConfiguration.java` | **Créé.** Le bean `Clock` que le projet n'a pas. | 2 |
| `backend/src/main/java/com/mapidf/controllers/vehicles/VehiclesController.java` | **Modifié.** Constructeur explicite, cache d'octets, en-têtes. | 2 |
| `backend/src/test/java/com/mapidf/controllers/vehicles/VehiclesControllerIT.java` | **Modifié.** Deux IT ajoutés ; les trois existants ne changent pas. | 2 |
| `backend/src/main/java/com/mapidf/controllers/disruptions/DisruptionsController.java` | **Modifié.** Cache d'objet, en-têtes. | 3 |
| `backend/src/test/java/com/mapidf/controllers/disruptions/DisruptionsControllerIT.java` | **Modifié.** Deux IT ajoutés. | 3 |
| `backend/src/main/java/com/mapidf/controllers/stations/StationsController.java` | **Modifié.** Cache d'objet **à clé**, en-têtes. | 4 |
| `backend/src/test/java/com/mapidf/controllers/stations/StationsControllerIT.java` | **Modifié.** Deux IT ajoutés, dont l'isolement entre stations. | 4 |
| `docs/roadmap.md` | **Modifié.** PERF-3 à `fait`, SEC-3 hérite du micro-cache nginx. | 5 |

**Pourquoi `com.mapidf.controllers.support`** : le composant n'est utilisé que par des contrôleurs, et le nom du paquet le dit. Il ne descend pas dans `services`, qui porte du domaine (`StationDepartureService`).

---

### Task 1: Le composant `ResponseCache`

Tâche entièrement isolée : aucun contrôleur n'est touché, rien n'est câblé dans Spring. Le composant est une classe ordinaire, instanciable partout.

**Files:**
- Create: `backend/src/main/java/com/mapidf/controllers/support/ResponseCache.java`
- Test: `backend/src/test/java/com/mapidf/controllers/support/ResponseCacheTest.java`

**Interfaces:**
- Consumes: rien.
- Produces: `ResponseCache<K, V>` avec le constructeur `ResponseCache(Clock clock, String name, MeterRegistry meters)`, et deux méthodes : `V get(List<Object> sources, Function<Instant, V> compute)` (sans clé) et `V get(K key, List<Object> sources, Function<Instant, V> compute)` (avec clé). Les tâches 2 à 4 n'utilisent que ça.

- [ ] **Step 1 : Écrire les six tests qui échouent**

Créer `backend/src/test/java/com/mapidf/controllers/support/ResponseCacheTest.java` :

```java
package com.mapidf.controllers.support;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ResponseCacheTest {

    /** Java n'a pas d'horloge mutable : sans elle, éprouver la frontière de seconde imposerait
     *  de dormir, donc un test lent et instable. */
    private static final class TestClock extends Clock {
        private Instant now;

        TestClock(Instant now) {
            this.now = now;
        }

        void set(Instant next) {
            this.now = next;
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    private static final Instant T0 = Instant.parse("2026-08-12T08:00:00.400Z");

    private TestClock clock;
    private MeterRegistry meters;
    private ResponseCache<String, String> cache;
    private AtomicInteger calls;

    @BeforeEach
    void setUp() {
        clock = new TestClock(T0);
        meters = new SimpleMeterRegistry();
        cache = new ResponseCache<>(clock, "test", meters);
        calls = new AtomicInteger();
    }

    private String compute(Instant now) {
        calls.incrementAndGet();
        return "valeur@" + now;
    }

    @Test
    void reusesTheEntryForTheSameSourcesWithinTheSameSecond() {
        Object source = new Object();

        String first = cache.get(List.of(source), this::compute);
        String second = cache.get(List.of(source), this::compute);

        assertThat(calls).hasValue(1);
        assertThat(second).isEqualTo(first);
    }

    @Test
    void recomputesWhenASourceIsAnEqualButFreshInstance() {
        // Le cœur du chantier : les instantanés sont des records, donc porteurs d'un equals
        // structurel. C'est l'IDENTITÉ qu'on veut, parce qu'un poll publie une instance neuve.
        // Ce test rougit si quelqu'un « corrige » == en equals.
        record Snapshot(String value) { }

        cache.get(List.of(new Snapshot("x")), this::compute);
        cache.get(List.of(new Snapshot("x")), this::compute);

        assertThat(calls).hasValue(2);
    }

    @Test
    void recomputesOnTheNextSecondEvenWithUnchangedSources() {
        Object source = new Object();
        cache.get(List.of(source), this::compute);

        clock.set(Instant.parse("2026-08-12T08:00:01.000Z"));
        cache.get(List.of(source), this::compute);

        assertThat(calls).hasValue(2);
    }

    @Test
    void keepsDistinctKeysIndependent() {
        Object source = new Object();

        cache.get("A", List.of(source), this::compute);
        cache.get("B", List.of(source), this::compute);
        cache.get("A", List.of(source), this::compute);

        assertThat(calls).hasValue(2);
    }

    @Test
    void handsTheComputationTheInstantThatKeyedTheEntry() {
        // Le calcul reçoit l'instant PLEIN (08:00:00.400), pas la seconde tronquée : la position
        // servie est la plus fraîche possible. Mais les deux viennent du MÊME appel à l'horloge,
        // sans quoi un calcul fait à 08:00:01.001 pourrait s'enregistrer sous la seconde 08:00:00.
        Object source = new Object();

        String value = cache.get(List.of(source), this::compute);

        assertThat(value).isEqualTo("valeur@" + T0);

        clock.set(Instant.parse("2026-08-12T08:00:00.999Z"));
        assertThat(cache.get(List.of(source), this::compute)).isEqualTo(value);
        assertThat(calls).hasValue(1);
    }

    @Test
    void countsHitsAndMisses() {
        Object source = new Object();

        cache.get(List.of(source), this::compute);
        cache.get(List.of(source), this::compute);
        cache.get(List.of(source), this::compute);

        assertThat(meters.counter("mapidf.cache.misses", "cache", "test").count()).isEqualTo(1.0);
        assertThat(meters.counter("mapidf.cache.hits", "cache", "test").count()).isEqualTo(2.0);
    }
}
```

- [ ] **Step 2 : Lancer les tests et vérifier qu'ils échouent**

```bash
cd backend && JAVA_HOME=~/.jdks/temurin-25.0.4 ./mvnw test -Dtest=ResponseCacheTest
```

Attendu : **échec de compilation**, `cannot find symbol: class ResponseCache`. C'est l'échec correct à ce stade — la classe n'existe pas encore.

- [ ] **Step 3 : Écrire l'implémentation minimale**

Créer `backend/src/main/java/com/mapidf/controllers/support/ResponseCache.java` :

```java
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
```

- [ ] **Step 4 : Lancer les tests et vérifier qu'ils passent**

```bash
cd backend && JAVA_HOME=~/.jdks/temurin-25.0.4 ./mvnw test -Dtest=ResponseCacheTest
```

Attendu : `Tests run: 6, Failures: 0, Errors: 0`.

- [ ] **Step 5 : Prouver le test n°2 par une mutation de contrôle**

Un test écrit sur du code déjà correct peut passer sans rien vérifier. Remplacer temporairement, dans `sameInstances`, `cached.get(i) != current.get(i)` par `!java.util.Objects.equals(cached.get(i), current.get(i))`, puis relancer.

Attendu : **`recomputesWhenASourceIsAnEqualButFreshInstance` échoue** (`expected: 2 but was: 1`). Rétablir `!=` immédiatement et relancer pour retrouver les six au vert. Si le test ne rougit pas, il ne protège rien et il faut le corriger avant de continuer.

- [ ] **Step 6 : Commit**

```bash
git add backend/src/main/java/com/mapidf/controllers/support/ResponseCache.java \
        backend/src/test/java/com/mapidf/controllers/support/ResponseCacheTest.java
git commit -m "feat(perf-3): cache de réponse invalidé par l'identité des instantanés

Un TTL pur retarderait d'une seconde la donnée d'un poll frais, et casserait
les IT de contrôleur qui pollent puis interrogent dans la même seconde. La
validité d'une entrée tient donc à l'identité de ses sources autant qu'à la
seconde courante."
```

---

### Task 2: `/vehicles` — cache d'octets

**Files:**
- Create: `backend/src/main/java/com/mapidf/configurations/ClockConfiguration.java`
- Modify: `backend/src/main/java/com/mapidf/controllers/vehicles/VehiclesController.java` (le fichier entier, 46 lignes)
- Test: `backend/src/test/java/com/mapidf/controllers/vehicles/VehiclesControllerIT.java` (ajouts en fin de classe)

**Interfaces:**
- Consumes: `ResponseCache<K, V>` de la tâche 1.
- Produces: le bean `Clock` (`java.time.Clock`), consommé tel quel par les tâches 3 et 4.

**Ce qui protège les trois IT existants** — à comprendre avant de commencer : le contrôleur est un singleton, son cache survit donc d'un test à l'autre. Deux mécanismes empêchent la pollution, et il ne faut casser ni l'un ni l'autre. Le `setup()` de chaque IT appelle `staticService.publishFromDatabase()` ([VehiclesControllerIT.java:73](../../../backend/src/test/java/com/mapidf/controllers/vehicles/VehiclesControllerIT.java#L73)), qui republie un `NetworkSnapshot` neuf ; et il appelle `poller.pollOnce(...)`, qui publie un `RtSnapshot` neuf. Chaque test démarre donc sur deux sources inédites, soit un défaut de cache garanti.

- [ ] **Step 1 : Écrire les deux IT qui échouent**

Ajouter à la fin de `VehiclesControllerIT`, sans toucher aux trois tests existants :

```java
    @Test
    void marksTheResponseAsNeverStorable() throws Exception {
        // Sans en-tête, un proxy peut cacher un 200 de façon heuristique et figer les trains
        // chez tous les clients. Le cache de PERF-3 est serveur ; celui des intermédiaires est
        // interdit.
        mockMvc.perform(get("/vehicles"))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
    }

    @Test
    void servesAFreshPollWithoutWaitingForTheNextSecond() throws Exception {
        // La propriété qui interdit un cache à TTL : deux polls et deux requêtes dans la même
        // seconde doivent donner deux réponses différentes.
        Instant first = Instant.parse("2026-08-12T08:00:00Z");
        poller.pollOnce(url -> new ByteArrayInputStream(
            "{}".getBytes(StandardCharsets.UTF_8)), first);
        String before = mockMvc.perform(get("/vehicles"))
            .andReturn().getResponse().getContentAsString();

        Instant later = Instant.parse("2026-08-12T09:00:00Z");
        poller.pollOnce(url -> new ByteArrayInputStream(
            "{}".getBytes(StandardCharsets.UTF_8)), later);
        String after = mockMvc.perform(get("/vehicles"))
            .andReturn().getResponse().getContentAsString();

        assertThat(Instant.parse(JSON.readTree(before).path("asOf").asString())).isEqualTo(first);
        assertThat(Instant.parse(JSON.readTree(after).path("asOf").asString())).isEqualTo(later);
    }
```

Ajouter les imports manquants en tête de fichier :

```java
import org.springframework.http.MediaType;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
```

- [ ] **Step 2 : Lancer les IT et vérifier lequel échoue**

```bash
cd backend && JAVA_HOME=~/.jdks/temurin-25.0.4 ./mvnw verify -Dit.test=VehiclesControllerIT -DfailIfNoTests=false
```

Attendu : `marksTheResponseAsNeverStorable` **échoue** (aucun en-tête `Cache-Control` posé aujourd'hui). `servesAFreshPollWithoutWaitingForTheNextSecond` **passe déjà** — il n'y a pas encore de cache à tromper. C'est normal et voulu : ce test est un **filet de régression**, écrit avant le cache précisément pour qu'il rougisse si l'étape 3 est mal faite.

- [ ] **Step 3 : Créer le bean `Clock`**

Créer `backend/src/main/java/com/mapidf/configurations/ClockConfiguration.java` :

```java
package com.mapidf.configurations;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ClockConfiguration {

    /** Le projet n'avait pas d'horloge injectable : les contrôleurs appelaient Instant.now(). */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
```

- [ ] **Step 4 : Réécrire `VehiclesController`**

Remplacer intégralement `backend/src/main/java/com/mapidf/controllers/vehicles/VehiclesController.java` :

```java
package com.mapidf.controllers.vehicles;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.mapidf.controllers.support.ResponseCache;
import com.mapidf.controllers.vehicles.VehiclesResponse.VehicleDto;
import com.mapidf.network.LineRegistry;
import com.mapidf.network.NetworkSnapshot;
import com.mapidf.network.TrackedLine;
import com.mapidf.position.PositionEngine;
import com.mapidf.position.Vehicle;
import com.mapidf.rt.RealtimePoller;
import com.mapidf.rt.RtSnapshot;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

/**
 * Tous les véhicules du réseau suivi en un appel : le front fait UN poll toutes les 4 s, pas
 * seize. Aucune requête SQL — registry en mémoire et snapshot temps réel.
 *
 * <p>Les positions dépendent de l'instant, donc elles sont recalculées ; mais au plus une fois
 * par seconde, la réponse étant mémorisée SÉRIALISÉE. C'est le seul des trois endpoints à cacher
 * des octets : lui seul porte ~705 objets, dont la sérialisation est du même ordre de grandeur
 * que le calcul et, contrairement à lui, croît avec le nombre de clients.
 */
@RestController
public class VehiclesController {

    private final LineRegistry registry;
    private final PositionEngine positionEngine;
    private final RealtimePoller poller;
    private final ObjectMapper json;
    private final ResponseCache<Void, byte[]> cache;

    public VehiclesController(LineRegistry registry, PositionEngine positionEngine,
                              RealtimePoller poller, ObjectMapper json,
                              Clock clock, MeterRegistry meters) {
        this.registry = registry;
        this.positionEngine = positionEngine;
        this.poller = poller;
        this.json = json;
        this.cache = new ResponseCache<>(clock, "vehicles", meters);
    }

    @GetMapping("/vehicles")
    public ResponseEntity<byte[]> vehicles() {
        // Les deux instantanés sont lus UNE fois, et servent à la fois de clé et d'entrée au
        // calcul. Les relire dans le lambda ouvrirait une fenêtre : un poll survenu entre-temps
        // ferait enregistrer une réponse fraîche sous l'identité de l'ancien instantané, donc
        // périmée jusqu'au poll suivant et non jusqu'à la seconde suivante.
        RtSnapshot snapshot = poller.current();
        NetworkSnapshot network = registry.current();

        byte[] body = cache.get(List.of(snapshot, network),
            now -> json.writeValueAsBytes(build(snapshot, network, now)));

        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_JSON)
            .cacheControl(CacheControl.noStore())
            .body(body);
    }

    private VehiclesResponse build(RtSnapshot snapshot, NetworkSnapshot network, Instant now) {
        List<VehicleDto> vehicles = new ArrayList<>();
        for (TrackedLine line : network.lines()) {
            for (Vehicle vehicle : positionEngine.computeAll(
                    line, snapshot.forLine(line.siriLineRef()), now)) {
                vehicles.add(VehicleDto.from(vehicle));
            }
        }
        return new VehiclesResponse(snapshot.dataDate(), poller.inServiceNow(), vehicles);
    }
}
```

Noter la disparition de `@AllArgsConstructor` : Lombok ne sait pas initialiser `cache` à partir de `clock`, d'où le constructeur explicite.

**Un piège d'encodage à connaître, propre à ce seul endpoint.** En rendant `byte[]`, la réponse ne passe plus par le convertisseur Jackson, qui posait le jeu de caractères. En production c'est sans effet — JSON est de l'UTF-8 par spécification (RFC 8259) et tout client le décode ainsi. Mais `MockHttpServletResponse.getContentAsString()`, lui, décode avec l'encodage déclaré sur la réponse et retombe sur ISO-8859-1 à défaut. Les fixtures de `VehiclesControllerIT` sont purement ASCII (« Gamma », « Villejuif »), donc rien ne se verra ici. Si un jour une assertion sur `/vehicles` bute sur des accents en mojibake, la cause est là, et le remède est `.contentType(MediaType.valueOf("application/json;charset=UTF-8"))` — surtout pas un changement côté front. `/disruptions` et `/stations/{id}/departures` ne sont pas concernés : ils continuent de passer par Jackson.

- [ ] **Step 5 : Lancer les IT et vérifier que les cinq passent**

```bash
cd backend && JAVA_HOME=~/.jdks/temurin-25.0.4 ./mvnw verify -Dit.test=VehiclesControllerIT -DfailIfNoTests=false
```

Attendu : `Tests run: 5, Failures: 0, Errors: 0`. Les **trois tests préexistants doivent passer sans avoir été modifiés** — c'est la preuve que le cache n'a pas retardé la donnée d'un poll.

- [ ] **Step 6 : Commit**

```bash
git add backend/src/main/java/com/mapidf/configurations/ClockConfiguration.java \
        backend/src/main/java/com/mapidf/controllers/vehicles/VehiclesController.java \
        backend/src/test/java/com/mapidf/controllers/vehicles/VehiclesControllerIT.java
git commit -m "perf(perf-3): /vehicles sert des octets mémorisés à la seconde

La sérialisation de ~705 DTO est du même ordre que le calcul JTS et, elle,
croît avec le nombre de clients : la mémoriser rend le coût plat. Cache-Control
no-store interdit aux intermédiaires de figer les trains."
```

---

### Task 3: `/disruptions` — cache d'objet

Même motif que la tâche 2, à deux différences près : la valeur cachée est le **record**, pas des octets (charge utile deux ordres de grandeur plus petite, § 2.5), et il n'y a donc pas d'`ObjectMapper` à injecter.

**Files:**
- Modify: `backend/src/main/java/com/mapidf/controllers/disruptions/DisruptionsController.java`
- Test: `backend/src/test/java/com/mapidf/controllers/disruptions/DisruptionsControllerIT.java` (ajouts en fin de classe)

**Interfaces:**
- Consumes: `ResponseCache<K, V>` (tâche 1), bean `Clock` (tâche 2).
- Produces: rien que d'autres tâches consomment.

- [ ] **Step 1 : Écrire les deux IT qui échouent**

Ajouter à la fin de `DisruptionsControllerIT` :

```java
    @Test
    void marksTheResponseAsNeverStorable() throws Exception {
        mockMvc.perform(get("/disruptions"))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
    }

    @Test
    void servesAFreshPollWithoutWaitingForTheNextSecond() throws Exception {
        // Un instantané vide après un instantané peuplé, dans la même seconde : la ligne 9 doit
        // disparaître immédiatement, pas à la seconde suivante.
        mockMvc.perform(get("/disruptions"))
            .andExpect(jsonPath("$.lines", hasSize(1)));

        poller.pollOnce(url -> new ByteArrayInputStream(
            "{}".getBytes(StandardCharsets.UTF_8)), Instant.now());

        mockMvc.perform(get("/disruptions"))
            .andExpect(jsonPath("$.lines", hasSize(0)));
    }
```

Ajouter les imports manquants en tête de fichier :

```java
import org.springframework.http.MediaType;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
```

(`content`, `jsonPath`, `hasSize`, `status`, `ByteArrayInputStream`, `StandardCharsets` et `Instant` y sont déjà.)

**Avant d'écrire ce test, vérifier le compte attendu.** Le `FEED` du fichier déclare des perturbations sur la ligne 9 et sur la 7, dont certaines aux périodes révolues. Lancer d'abord le test avec `hasSize(1)` : s'il échoue en annonçant un autre nombre, **corriger le `hasSize` attendu**, pas le `FEED` — ce test ne porte pas sur le filtre « en cours », que d'autres tests du fichier couvrent déjà.

- [ ] **Step 2 : Lancer les IT et vérifier lequel échoue**

```bash
cd backend && JAVA_HOME=~/.jdks/temurin-25.0.4 ./mvnw verify -Dit.test=DisruptionsControllerIT -DfailIfNoTests=false
```

Attendu : `marksTheResponseAsNeverStorable` **échoue** faute d'en-tête. `servesAFreshPollWithoutWaitingForTheNextSecond` **passe déjà** — filet de régression, comme en tâche 2.

- [ ] **Step 3 : Modifier le contrôleur**

Dans `DisruptionsController`, remplacer l'annotation `@AllArgsConstructor` et la méthode `disruptions()`. Le reste du fichier (`disruptedStations`, `toItem`) ne change pas.

Supprimer `import lombok.AllArgsConstructor;` et l'annotation `@AllArgsConstructor`. Ajouter :

```java
import java.time.Clock;
import java.util.List;

import com.mapidf.controllers.support.ResponseCache;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
```

Remplacer les deux champs et la méthode `disruptions()` par :

```java
    private final LineRegistry registry;
    private final DisruptionPoller poller;
    private final ResponseCache<Void, DisruptionsResponse> cache;

    public DisruptionsController(LineRegistry registry, DisruptionPoller poller,
                                 Clock clock, MeterRegistry meters) {
        this.registry = registry;
        this.poller = poller;
        this.cache = new ResponseCache<>(clock, "disruptions", meters);
    }

    @GetMapping("/disruptions")
    public ResponseEntity<DisruptionsResponse> disruptions() {
        // Le record est mémorisé tel quel, et non sérialisé : la charge utile est deux ordres de
        // grandeur sous celle de /vehicles, donc les octets n'achèteraient rien — et le type
        // paramétré reste lisible par un générateur de schéma (QUA-7).
        DisruptionSnapshot snapshot = poller.current();
        NetworkSnapshot network = registry.current();

        DisruptionsResponse body = cache.get(List.of(snapshot, network),
            now -> build(snapshot, network, now));

        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_JSON)
            .cacheControl(CacheControl.noStore())
            .body(body);
    }

    private DisruptionsResponse build(DisruptionSnapshot snapshot, NetworkSnapshot network,
                                      Instant now) {
        List<LineDisruptions> lines = new ArrayList<>();
        for (TrackedLine line : network.lines()) {
            List<Disruption> active = snapshot.forLine(line.id(), now);
            if (active.isEmpty()) {
                continue;
            }
            lines.add(new LineDisruptions(line.id(),
                // La liste est déjà triée par gravité décroissante par le snapshot.
                active.getFirst().severity().name(),
                active.stream().map(DisruptionsController::toItem).toList()));
        }
        return new DisruptionsResponse(now, lines, disruptedStations(snapshot, network, now));
    }
```

L'ancienne méthode lisait `registry.current()` en son sein ; elle reçoit désormais le `NetworkSnapshot` capté avant l'appel au cache, pour la raison expliquée dans `VehiclesController`.

- [ ] **Step 4 : Lancer les IT et vérifier qu'ils passent tous**

```bash
cd backend && JAVA_HOME=~/.jdks/temurin-25.0.4 ./mvnw verify -Dit.test=DisruptionsControllerIT -DfailIfNoTests=false
```

Attendu : tous verts, les préexistants **sans modification**.

- [ ] **Step 5 : Commit**

```bash
git add backend/src/main/java/com/mapidf/controllers/disruptions/DisruptionsController.java \
        backend/src/test/java/com/mapidf/controllers/disruptions/DisruptionsControllerIT.java
git commit -m "perf(perf-3): /disruptions mémorise son record à la seconde

Pas d'octets ici : la charge utile est deux ordres de grandeur sous celle de
/vehicles, et le type paramétré reste lisible par un générateur de schéma."
```

---

### Task 4: `/stations/{id}/departures` — cache d'objet à clé

La seule tâche qui exerce la surcharge **avec clé**. Trois sources, parce que la réponse mêle passages temps réel et perturbations de quai.

**Files:**
- Modify: `backend/src/main/java/com/mapidf/controllers/stations/StationsController.java`
- Test: `backend/src/test/java/com/mapidf/controllers/stations/StationsControllerIT.java` (ajouts en fin de classe)

**Interfaces:**
- Consumes: `ResponseCache<K, V>` (tâche 1), bean `Clock` (tâche 2).
- Produces: rien.

- [ ] **Step 1 : Écrire les deux IT qui échouent**

Ajouter à la fin de `StationsControllerIT` :

```java
    @Test
    void marksTheResponseAsNeverStorable() throws Exception {
        mockMvc.perform(get("/stations/STC/departures"))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
    }

    @Test
    void keepsStationsIndependentWithinTheSameSecond() throws Exception {
        // Le cache est indexé par station : deux stations interrogées dans la même seconde ne
        // doivent pas se servir la réponse l'une de l'autre.
        mockMvc.perform(get("/stations/STC/departures"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.stationName").value("Correspondance"));

        mockMvc.perform(get("/stations/ST1/departures"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.stationName").value("Alpha"));
    }
```

Ajouter les imports manquants en tête de fichier :

```java
import org.springframework.http.MediaType;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
```

(`jsonPath` et `status` y sont déjà.)

**Identifiants de la fixture** `gtfs-branch.zip`, lus le 2026-08-12 dans son `stops.txt` — les stations (`location_type=1`) sont `ST1` « Alpha », `STC` « Correspondance », `ST3` « Gamma », `PT1` « Nord », `PT3` « Sud », `PT4` « Villejuif », `PT5` « Ivry ». Ne pas en inventer d'autres.

**Pourquoi pas de test sur une station inconnue** : `StationsControllerIT` en a déjà un (`NOPE`, ligne 165). En ajouter un second qui appellerait deux fois de suite ne prouverait rien — l'entrée n'étant enregistrée qu'**après** un calcul réussi, un `requireStation` déplacé après le cache donnerait quand même deux 404. Un test incapable de rougir n'a pas sa place ici.

- [ ] **Step 2 : Lancer les IT et vérifier lesquels échouent**

```bash
cd backend && JAVA_HOME=~/.jdks/temurin-25.0.4 ./mvnw verify -Dit.test=StationsControllerIT -DfailIfNoTests=false
```

Attendu : `marksTheResponseAsNeverStorable` **échoue** faute d'en-tête. `keepsStationsIndependentWithinTheSameSecond` **passe déjà** — filet de régression pour l'étape 3.

- [ ] **Step 3 : Modifier le contrôleur**

Dans `StationsController`, supprimer `import lombok.AllArgsConstructor;` et l'annotation. Ajouter :

```java
import java.time.Clock;

import com.mapidf.controllers.support.ResponseCache;
import com.mapidf.disruptions.DisruptionSnapshot;
import com.mapidf.network.NetworkSnapshot;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
```

Remplacer les champs et la méthode `departures` par :

```java
    private final LineRegistry registry;
    private final RealtimePoller poller;
    private final DisruptionPoller disruptionPoller;
    private final StationDepartureService departureService;
    private final ResponseCache<String, DeparturesResponse> cache;

    public StationsController(LineRegistry registry, RealtimePoller poller,
                              DisruptionPoller disruptionPoller,
                              StationDepartureService departureService,
                              Clock clock, MeterRegistry meters) {
        this.registry = registry;
        this.poller = poller;
        this.disruptionPoller = disruptionPoller;
        this.departureService = departureService;
        this.cache = new ResponseCache<>(clock, "departures", meters);
    }

    @GetMapping("/stations/{id}/departures")
    public ResponseEntity<DeparturesResponse> departures(@PathVariable String id) {
        // requireStation AVANT le cache : un identifiant inconnu lève, donc la map reste bornée
        // par le nombre de stations du registry — pas d'éviction à écrire, pas de saturation par
        // identifiant forgé.
        Station station = registry.requireStation(id);

        RtSnapshot rt = poller.current();
        DisruptionSnapshot disruptions = disruptionPoller.current();
        NetworkSnapshot network = registry.current();

        DeparturesResponse body = cache.get(id, List.of(rt, disruptions, network),
            now -> build(station, rt, disruptions, network, now));

        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_JSON)
            .cacheControl(CacheControl.noStore())
            .body(body);
    }

    private DeparturesResponse build(Station station, RtSnapshot rt,
                                     DisruptionSnapshot disruptionSnapshot,
                                     NetworkSnapshot network, Instant now) {
        // Seules les lignes qui desservent cette station : jusqu'à 5 sur une correspondance.
        List<TrackedLine> lines = station.lineIds().stream()
            .map(lineId -> network.linesById().get(lineId))
            .filter(Objects::nonNull)
            .toList();
        DeparturesResponse departures = departureService.departures(
            station, lines, rt, now, PASSAGES_PER_DIRECTION);
        return new DeparturesResponse(departures.stationName(), departures.lines(),
            disruptionsOf(station, disruptionSnapshot, now));
    }
```

Et adapter `disruptionsOf` pour recevoir l'instantané au lieu de le relire :

```java
    /**
     * Perturbations en cours des quais de la station, dédoublonnées : une même perturbation vise
     * souvent plusieurs quais du même nom de station.
     */
    private List<DisruptionsResponse.Item> disruptionsOf(Station station,
                                                         DisruptionSnapshot snapshot, Instant now) {
        Map<String, Disruption> byId = new LinkedHashMap<>();
        for (String platformId : station.platformIds()) {
            for (Disruption disruption : snapshot.forStop(PositionEngine.stopKey(platformId), now)) {
                byId.putIfAbsent(disruption.id(), disruption);
            }
        }
        return byId.values().stream()
            .map(disruption -> new DisruptionsResponse.Item(disruption.severity().name(),
                disruption.cause(), disruption.title(), disruption.shortMessage(),
                disruption.detail()))
            .toList();
    }
```

Le `var snapshot = disruptionPoller.current();` de l'ancienne version disparaît : l'instantané est capté une fois dans `departures`, avec les deux autres.

- [ ] **Step 4 : Lancer les IT et vérifier qu'ils passent tous**

```bash
cd backend && JAVA_HOME=~/.jdks/temurin-25.0.4 ./mvnw verify -Dit.test=StationsControllerIT -DfailIfNoTests=false
```

Attendu : tous verts, les préexistants sans modification.

- [ ] **Step 5 : Vérification complète**

```bash
cd backend && JAVA_HOME=~/.jdks/temurin-25.0.4 ./mvnw verify
```

Attendu : `BUILD SUCCESS`, avec **plus** de tests qu'avant le chantier (104 UT + 45 IT au 2026-08-11 ; ce plan ajoute 6 UT et 6 IT — deux par contrôleur —, soit 110 + 51). Si le compte diffère, en comprendre la raison avant de committer.

- [ ] **Step 6 : Commit**

```bash
git add backend/src/main/java/com/mapidf/controllers/stations/StationsController.java \
        backend/src/test/java/com/mapidf/controllers/stations/StationsControllerIT.java
git commit -m "perf(perf-3): /stations/{id}/departures mémorise par station

Seul endpoint paramétré du chantier : requireStation lève avant le cache, donc
la map reste bornée par le registry et aucun identifiant forgé n'y entre."
```

---

### Task 5: Mesure du partage `C`/`S`, et documentation

La spec raisonne sur des estimations (§ 2.5) et assume de ne pas en faire un préalable bloquant. Maintenant que le code est en place, la mesure coûte quelques minutes et **soit confirme la répartition, soit la corrige d'un paramètre de type**.

**Files:**
- Create (temporaire, supprimé à l'étape 3) : `backend/src/test/java/com/mapidf/controllers/support/SerializationCostProbe.java`
- Modify: `docs/roadmap.md`

**Interfaces:** aucune.

- [ ] **Step 1 : Mesurer le coût de sérialisation**

Créer une sonde jetable `backend/src/test/java/com/mapidf/controllers/support/SerializationCostProbe.java` :

```java
package com.mapidf.controllers.support;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.mapidf.controllers.vehicles.VehiclesResponse;
import com.mapidf.controllers.vehicles.VehiclesResponse.VehicleDto;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** Sonde jetable : combien coûte la sérialisation de 705 véhicules ? À supprimer après lecture. */
class SerializationCostProbe {

    @Test
    void measure() {
        ObjectMapper json = new ObjectMapper();
        Instant now = Instant.parse("2026-08-12T08:00:00Z");
        List<VehicleDto> vehicles = new ArrayList<>();
        for (int i = 0; i < 705; i++) {
            vehicles.add(new VehicleDto("J" + i, "9", 48.85 + i * 1e-5, 2.35 + i * 1e-5,
                123.4, "ON_TIME", "Mairie de Montreuil", "Q:" + i + ":",
                now.plusSeconds(60), now, "RELIABLE"));
        }
        VehiclesResponse response = new VehiclesResponse(now, true, vehicles);

        for (int i = 0; i < 200; i++) {
            json.writeValueAsBytes(response);
        }

        long start = System.nanoTime();
        int runs = 500;
        int bytes = 0;
        for (int i = 0; i < runs; i++) {
            bytes = json.writeValueAsBytes(response).length;
        }
        long elapsed = System.nanoTime() - start;

        System.out.printf("S = %.2f ms/appel, charge utile %d ko%n",
            elapsed / 1e6 / runs, bytes / 1024);
    }
}
```

Lancer :

```bash
cd backend && JAVA_HOME=~/.jdks/temurin-25.0.4 ./mvnw test -Dtest=SerializationCostProbe
```

Noter la valeur de `S` affichée. Les 200 premiers appels sont là pour que le JIT ait compilé la boucle avant la mesure — sans eux le chiffre est plusieurs fois trop élevé.

- [ ] **Step 2 : Confronter à l'estimation de la spec**

La spec annonce `S` ≈ 1 à 3 ms et `C` ≈ 3 à 8 ms, d'où une bascule vers une dizaine de clients.

- Si `S` tombe dans la fourchette : la répartition tient, rien à changer dans le code.
- Si `S` est **nettement sous** 1 ms (disons < 0,3 ms) : le cache d'octets sur `/vehicles` n'achète presque rien, et la bascule part au-delà de la centaine de clients. Ce n'est **pas** une raison de revenir en arrière maintenant — le code est écrit, testé, et le sens de l'erreur reste bon —, mais il faut **corriger le § 2.5 de la spec** pour qu'il ne fasse pas autorité sur un chiffre faux.
- Dans tous les cas, remplacer dans le § 2.5 « **Estimations, pas mesures** » par la valeur mesurée, en datant la mesure.

- [ ] **Step 3 : Supprimer la sonde**

```bash
rm backend/src/test/java/com/mapidf/controllers/support/SerializationCostProbe.java
```

Une sonde jetable ne se commite pas : elle mesure une machine, pas un comportement, et rougirait un jour pour une raison sans rapport.

- [ ] **Step 4 : Mettre la roadmap à jour**

Dans `docs/roadmap.md` :

1. Ligne **PERF-3** (§ 4) : statut `à faire` → `**fait**`, avec un lien vers la spec, la mention que l'`ETag` a été écarté (il ne mordrait pas à cette granularité), que les trois endpoints chauds sont couverts et non le seul `/vehicles`, que seul `/vehicles` cache des octets, et la valeur de `S` mesurée à l'étape 1.
2. Ligne **SEC-3** (§ 1) : ajouter que le micro-cache nginx (`proxy_cache_valid 1s`) lui revient, et qu'il exigera `proxy_ignore_headers Cache-Control` ou le passage de `no-store` à `max-age=1, public` — même fichier et même préoccupation de bordure que `limit_req`.
3. Ligne **QUA-7** (§ 5) : ajouter que `/vehicles` rend désormais `ResponseEntity<byte[]>`, donc que sa forme de réponse devra être déclarée à la main au générateur ; les deux autres endpoints gardent leur type paramétré.
4. Section **« Ordre recommandé »**, point 3 : PERF-3 n'est plus à faire avec SEC-3 — le reformuler pour que SEC-3 y reste seul, en notant que le cache est déjà en place et que le quota à dimensionner s'en trouve allégé.

- [ ] **Step 5 : Vérification finale et commit**

```bash
cd backend && JAVA_HOME=~/.jdks/temurin-25.0.4 ./mvnw verify
```

Attendu : `BUILD SUCCESS`, et `git status` ne montre **aucune** trace de `SerializationCostProbe`.

```bash
git add docs/roadmap.md docs/superpowers/specs/2026-08-12-perf-3-cache-serveur-vehicles-design.md
git commit -m "docs(perf-3): PERF-3 fait, le micro-cache nginx revient à SEC-3

Le partage entre calcul et sérialisation est désormais mesuré, et le § 2.5 de
la spec ne raisonne plus sur une estimation."
```

---

## Ce que ce plan ne fait pas

- **Aucun `ETag`, aucun 304.** Le corps de chaque réponse dépend de l'instant : à cette granularité, un validateur changerait à chaque appel et un client qui poll à 4 s n'obtiendrait jamais de 304 (§ 2.1).
- **Aucune configuration nginx.** Le micro-cache part avec SEC-3, et le `no-store` de ce plan l'empêche volontairement d'ici là (§ 8).
- **Aucun changement côté front.** Le contrat JSON des trois endpoints est inchangé au champ près ; `getJson` continue de lire du `application/json`.
- **Aucune dépendance ajoutée au `pom.xml`.** Spring Cache et Caffeine ont été examinés puis écartés : ils savent expirer, pas observer une source (§ 2.2).
