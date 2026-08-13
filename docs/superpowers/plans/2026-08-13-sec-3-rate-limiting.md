# SEC-3 — Quota par IP sur les endpoints publics : plan d'implémentation

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** borner à 600 requêtes par minute et par IP les quatre endpoints publics du backend, dans toutes les topologies de déploiement, sans gêner un usage humain ni les tests existants.

**Architecture:** un `RateLimiter` à fenêtre fixe d'une minute (`ConcurrentHashMap` indexée par IP, balayage une fois par minute), consommé par un `HandlerInterceptor` qui traduit un refus en `429` via l'`ApiExceptionHandler` déjà en place. La vraie IP cliente vient du `RemoteIpValve` de Tomcat (`server.forward-headers-strategy: native`), qui ne croit `X-Forwarded-For` que d'un proxy en adresse privée. nginx pose un second rideau `limit_req` dans la pile compose, et le port 8100 du backend se referme sur la loopback.

**Tech Stack:** Spring Boot 4.1, Java 25, Lombok, Micrometer, JUnit 5 + AssertJ + MockMvc + Testcontainers, nginx.

**Spec de référence :** [docs/superpowers/specs/2026-08-13-sec-3-rate-limiting-design.md](../specs/2026-08-13-sec-3-rate-limiting-design.md). En cas de désaccord entre ce plan et la spec, la spec gouverne — signale-le plutôt que de trancher seul.

## Global Constraints

- **`./mvnw` exige Java 25** : préfixer toute commande Maven par `JAVA_HOME=~/.jdks/temurin-25.0.4`. Le `java` du shell est en 21 et le build échouera sans ça.
- **Ne jamais démarrer ni arrêter le backend, le front ou Docker.** L'utilisateur les pilote depuis son IDE. Toute vérification qui exige une pile lancée est une **étape de recette à lui remettre**, pas une commande à exécuter. Seule exception explicitement prévue ici : un `docker run --rm` jetable pour `nginx -t` (Task 3), qui ne touche à aucun conteneur existant.
- **Jackson 3** (`tools.jackson.databind`, pas `com.fasterxml.jackson.databind`). Sur un `JsonNode`, `.asString()` et non `.asText()`. Les annotations restent en `com.fasterxml.jackson.annotation`.
- **Records pour les DTO et valeurs immuables**, Lombok pour le reste. `@Slf4j` pour les journaux.
- **Commentaires sobres** : seulement le « pourquoi » non évident, en une à deux lignes. Pas de commentaire qui paraphrase le code.
- **TDD strict sur le code neuf** : le test qui échoue d'abord, et on le **regarde échouer** avant d'implémenter. Chaque tâche le dit explicitement par une étape « lance-le et vérifie qu'il rougit ».
- **Messages de commit en français**, terminés par `Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>`.
- **`.env` n'est jamais commité** ; `.env.example` n'est **pas** modifié par ce chantier (décision du § 3 de la spec).
- Les mentions de la **Licence Mobilité** (art. 5.4 source, 5.7 neutralité) ne se retirent nulle part.
- **Aucune migration Flyway** n'est touchée par ce chantier.
- **Branche de travail : `feat/sec-3-rate-limiting`.** Ne pas committer sur `master`.

## Structure des fichiers

| Fichier | Rôle | Tâche |
|---|---|---|
| `backend/src/main/java/com/mapidf/controllers/support/RateLimiter.java` | **créé** — le compteur : fenêtre fixe, balayage, métrique, WARN borné | 1 |
| `backend/src/test/java/com/mapidf/controllers/support/TestClock.java` | **créé** — horloge mutable, extraite de `ResponseCacheTest` pour être partagée | 1 |
| `backend/src/test/java/com/mapidf/controllers/support/ResponseCacheTest.java` | **modifié** — utilise le `TestClock` extrait au lieu de sa copie privée | 1 |
| `backend/src/test/java/com/mapidf/controllers/support/RateLimiterTest.java` | **créé** — unitaire, horloge maîtrisée | 1 |
| `backend/src/main/java/com/mapidf/configurations/properties/RateLimitProperties.java` | **créé** — `app.ratelimit`, refuse un budget ≤ 0 | 2 |
| `backend/src/test/java/com/mapidf/configurations/properties/RateLimitPropertiesTest.java` | **créé** — le refus du budget invalide | 2 |
| `backend/src/main/java/com/mapidf/data/enums/ErrorCode.java` | **modifié** — `TOO_MANY_REQUESTS` | 2 |
| `backend/src/main/java/com/mapidf/controllers/support/RateLimitInterceptor.java` | **créé** — extraction de l'IP, exemption loopback, traduction HTTP | 2 |
| `backend/src/main/java/com/mapidf/configurations/WebMvcConfiguration.java` | **créé** — enregistre l'interceptor sur `/**` | 2 |
| `backend/src/main/resources/application.yml` | **modifié** — `forward-headers-strategy: native` et `app.ratelimit` | 2 |
| `backend/src/test/java/com/mapidf/controllers/support/RateLimitIT.java` | **créé** — 429 de bout en bout, corps, en-tête, métrique, exemption | 2 |
| `frontend/nginx.conf` | **modifié** — `limit_req` sur `/api/` | 3 |
| `docker-compose.yml` | **modifié** — port 8100 sur la loopback | 3 |
| `docs/roadmap.md`, `CLAUDE.md`, `README.md` | **modifiés** — clôture et pièges | 4 |

---

### Task 1 : le compteur `RateLimiter`

Unité pure, sans Spring ni HTTP. C'est là que vit toute la logique de fenêtre ; les tâches suivantes ne font que la brancher.

**Files:**
- Create: `backend/src/main/java/com/mapidf/controllers/support/RateLimiter.java`
- Create: `backend/src/test/java/com/mapidf/controllers/support/TestClock.java`
- Create: `backend/src/test/java/com/mapidf/controllers/support/RateLimiterTest.java`
- Modify: `backend/src/test/java/com/mapidf/controllers/support/ResponseCacheTest.java` (supprimer la classe interne `TestClock`, utiliser celle extraite)

**Interfaces:**
- Consomme : le bean `Clock` de `com.mapidf.configurations.ClockConfiguration` (posé par PERF-3) et un `MeterRegistry` Micrometer. Rien d'autre.
- Produit, pour la Task 2 :
  - `public RateLimiter(Clock clock, int budget, MeterRegistry meters)`
  - `public RateLimiter.Decision check(String key)`
  - `public record Decision(boolean allowed, long retryAfterSeconds)`
  - `int trackedKeys()` — visibilité paquet, pour le test d'éviction uniquement

---

- [ ] **Étape 1 : extraire `TestClock` de `ResponseCacheTest`**

`ResponseCacheTest` porte aujourd'hui une classe interne `private static final class TestClock extends Clock`. Deux tests vont en avoir besoin ; la dupliquer serait un défaut qu'une revue relèverait. Crée `backend/src/test/java/com/mapidf/controllers/support/TestClock.java` :

```java
package com.mapidf.controllers.support;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * Java n'a pas d'horloge mutable : sans elle, éprouver une frontière de fenêtre imposerait de
 * dormir, donc un test lent et instable.
 */
final class TestClock extends Clock {

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
```

Puis dans `ResponseCacheTest`, supprime la classe interne `TestClock` (et son commentaire, qui part avec elle dans le nouveau fichier) et l'import `java.time.ZoneId` / `java.time.ZoneOffset` s'ils deviennent inutilisés. Le reste du fichier est inchangé : même paquet, même nom de type, même API.

- [ ] **Étape 2 : vérifier que l'extraction n'a rien cassé**

```bash
JAVA_HOME=~/.jdks/temurin-25.0.4 ./mvnw -q test -Dtest=ResponseCacheTest
```

Attendu : `BUILD SUCCESS`, 9 tests verts. Si ça ne compile pas, c'est un import resté en trop dans `ResponseCacheTest`.

- [ ] **Étape 3 : écrire les tests du `RateLimiter`, qui ne compile pas encore**

Crée `backend/src/test/java/com/mapidf/controllers/support/RateLimiterTest.java` :

```java
package com.mapidf.controllers.support;

import java.time.Instant;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class RateLimiterTest {

    /** Milieu de seconde ET début de minute : la troncature à la minute doit se voir. */
    private static final Instant T0 = Instant.parse("2026-08-13T08:00:00.400Z");

    private static final int BUDGET = 3;

    private TestClock clock;
    private MeterRegistry meters;
    private RateLimiter limiter;

    @BeforeEach
    void setUp() {
        clock = new TestClock(T0);
        meters = new SimpleMeterRegistry();
        limiter = new RateLimiter(clock, BUDGET, meters);
    }

    private double rejections() {
        return meters.counter("mapidf.ratelimit.rejected").count();
    }

    @Test
    void laissePasserJusquAuBudget() {
        for (int i = 1; i <= BUDGET; i++) {
            assertThat(limiter.check("1.2.3.4").allowed())
                .as("requête %d sur un budget de %d", i, BUDGET)
                .isTrue();
        }
    }

    @Test
    void refuseLaRequeteQuiDepasseLeBudget() {
        for (int i = 0; i < BUDGET; i++) {
            limiter.check("1.2.3.4");
        }

        assertThat(limiter.check("1.2.3.4").allowed()).isFalse();
    }

    @Test
    void laFenetreSuivanteRouvreLeBudget() {
        for (int i = 0; i <= BUDGET; i++) {
            limiter.check("1.2.3.4");
        }
        assertThat(limiter.check("1.2.3.4").allowed()).isFalse();

        clock.set(T0.plusSeconds(60));

        assertThat(limiter.check("1.2.3.4").allowed()).isTrue();
    }

    @Test
    void laFenetreEstTronqueeALaMinuteEtNonGlissante() {
        clock.set(Instant.parse("2026-08-13T08:00:59.900Z"));
        for (int i = 0; i < BUDGET; i++) {
            limiter.check("1.2.3.4");
        }
        assertThat(limiter.check("1.2.3.4").allowed()).isFalse();

        // 200 ms plus tard seulement, mais de l'autre côté de la minute : budget neuf. C'est le
        // prix assumé de la fenêtre fixe, et il doit être constaté, pas subi.
        clock.set(Instant.parse("2026-08-13T08:01:00.100Z"));

        assertThat(limiter.check("1.2.3.4").allowed()).isTrue();
    }

    @Test
    void compteChaqueCleSeparement() {
        for (int i = 0; i <= BUDGET; i++) {
            limiter.check("1.2.3.4");
        }
        assertThat(limiter.check("1.2.3.4").allowed()).isFalse();

        assertThat(limiter.check("5.6.7.8").allowed()).isTrue();
    }

    @Test
    void rendLesSecondesRestantJusquALaFinDeLaFenetre() {
        assertThat(limiter.check("1.2.3.4").retryAfterSeconds()).isEqualTo(60);

        clock.set(Instant.parse("2026-08-13T08:00:30.400Z"));
        assertThat(limiter.check("1.2.3.4").retryAfterSeconds()).isEqualTo(30);

        clock.set(Instant.parse("2026-08-13T08:00:59.900Z"));
        assertThat(limiter.check("1.2.3.4").retryAfterSeconds()).isEqualTo(1);
    }

    @Test
    void evinceLesEntreesDUneFenetrePassee() {
        limiter.check("1.2.3.4");
        limiter.check("5.6.7.8");
        assertThat(limiter.trackedKeys()).isEqualTo(2);

        clock.set(T0.plusSeconds(60));
        limiter.check("9.10.11.12");

        // La map est indexée par une clé que l'appelant choisit : sans balayage elle croît avec
        // le nombre d'IP vues et ne décroît jamais.
        assertThat(limiter.trackedKeys()).isEqualTo(1);
    }

    @Test
    void compteLesRejets() {
        assertThat(rejections()).isZero();

        for (int i = 0; i <= BUDGET; i++) {
            limiter.check("1.2.3.4");
        }

        assertThat(rejections()).isEqualTo(1);

        limiter.check("1.2.3.4");

        assertThat(rejections()).isEqualTo(2);
    }
}
```

- [ ] **Étape 4 : lancer les tests et les regarder échouer**

```bash
JAVA_HOME=~/.jdks/temurin-25.0.4 ./mvnw -q test -Dtest=RateLimiterTest
```

Attendu : **échec de compilation**, `cannot find symbol: class RateLimiter`. C'est le rouge recherché : la classe n'existe pas encore.

- [ ] **Étape 5 : écrire `RateLimiter`**

Crée `backend/src/main/java/com/mapidf/controllers/support/RateLimiter.java` :

```java
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
     *  bord, et un balayage manqué sera rattrapé par la requête suivante. */
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
        // Une seule ligne par clé et par fenêtre, au franchissement exact : borné par
        // construction, donc un abuseur ne peut pas inonder les journaux. Une métrique ne sert à
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
```

- [ ] **Étape 6 : lancer les tests et les voir passer**

```bash
JAVA_HOME=~/.jdks/temurin-25.0.4 ./mvnw -q test -Dtest=RateLimiterTest
```

Attendu : `BUILD SUCCESS`, 8 tests verts.

- [ ] **Étape 7 : prouver que le test d'éviction peut rougir**

Un test écrit sur du code correct passe du premier coup, ce qui ne prouve rien. Commente temporairement les trois lignes du balayage (`if (!sweptWindow.equals(window)) { … }`) et relance :

```bash
JAVA_HOME=~/.jdks/temurin-25.0.4 ./mvnw -q test -Dtest=RateLimiterTest
```

Attendu : **`evinceLesEntreesDUneFenetrePassee` échoue** (`expected 1 but was 3`), les sept autres restent verts. Rétablis les lignes et relance pour retrouver le vert avant de committer. **Ne commite pas la version mutée.**

- [ ] **Étape 8 : commit**

```bash
git add backend/src/main/java/com/mapidf/controllers/support/RateLimiter.java \
        backend/src/test/java/com/mapidf/controllers/support/RateLimiterTest.java \
        backend/src/test/java/com/mapidf/controllers/support/TestClock.java \
        backend/src/test/java/com/mapidf/controllers/support/ResponseCacheTest.java
git commit -m "$(cat <<'EOF'
feat(sec-3): compteur de requêtes par clé sur une fenêtre fixe d'une minute

Fenêtre fixe et non seau à jetons : deux fois le budget en deux secondes à
cheval sur deux fenêtres, c'est assumé pour un limiteur d'abus. Le balayage
se déclenche au changement de fenêtre et non par clé neuve — la clé est
choisie par l'appelant, un balayage par clé serait quadratique.

TestClock est extrait de ResponseCacheTest pour être partagé par les deux.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 2 : le branchement HTTP

Le compteur devient un `429`. C'est ici que se jouent la résolution de l'IP cliente et l'exemption loopback — les deux points qui décident si le quota borne quelque chose ou rien du tout.

**Files:**
- Create: `backend/src/main/java/com/mapidf/configurations/properties/RateLimitProperties.java`
- Create: `backend/src/test/java/com/mapidf/configurations/properties/RateLimitPropertiesTest.java`
- Create: `backend/src/main/java/com/mapidf/controllers/support/RateLimitInterceptor.java`
- Create: `backend/src/main/java/com/mapidf/configurations/WebMvcConfiguration.java`
- Create: `backend/src/test/java/com/mapidf/controllers/support/RateLimitIT.java`
- Modify: `backend/src/main/java/com/mapidf/data/enums/ErrorCode.java`
- Modify: `backend/src/main/resources/application.yml`

**Interfaces:**
- Consomme, de la Task 1 : `new RateLimiter(Clock, int, MeterRegistry)` et `RateLimiter.Decision check(String)` avec ses accesseurs `allowed()` / `retryAfterSeconds()`.
- Consomme, de l'existant : `com.mapidf.exceptions.ApiException(HttpStatus, ErrorCode)`, `com.mapidf.data.enums.ErrorCode`, le bean `Clock` de `ClockConfiguration`, et `@ConfigurationPropertiesScan` déjà posé sur `MapIdfApplication` (aucun `@EnableConfigurationProperties` à ajouter).
- Produit : `RateLimitProperties#requestsPerMinute()`, la valeur d'énumération `ErrorCode.TOO_MANY_REQUESTS`.

---

- [ ] **Étape 1 : configurer `application.yml` — en premier, et ce n'est pas un détail d'ordre**

`RateLimitProperties` (étape 3) refuse un budget ≤ 0, et `@ConfigurationPropertiesScan` est déjà posé sur `MapIdfApplication`. Dès que la classe existera, une liaison sans propriété donnera `requestsPerMinute = 0` et **tous les IT échoueront au démarrage du contexte**. La configuration passe donc avant la classe qui la lit.

Dans `backend/src/main/resources/application.yml`, ajoute `forward-headers-strategy` sous `server:` (juste après `context-path`, avant le commentaire des ports) :

```yaml
server:
  servlet:
    context-path: /api
  # RemoteIpValve de Tomcat, et surtout PAS le ForwardedHeaderFilter de Spring (`framework`) :
  # lui seul a une notion de proxy de confiance. X-Forwarded-For n'est cru que s'il vient d'une
  # adresse privée (défaut server.tomcat.remoteip.internal-proxies), donc un client public qui
  # tape le port en direct ne peut pas forger son IP — sans quoi le quota SEC-3 ne bornerait rien.
  forward-headers-strategy: native
```

Et sous `app:`, à la suite de `network:` et `prim:` :

```yaml
  ratelimit:
    # Un onglet consomme ~31 req/min en pointe (15 pour /vehicles, 15 pour les passages d'une
    # fiche ouverte, 1 pour /disruptions) : 600 laisse une vingtaine d'onglets sur une même
    # adresse. Ce quota arrête une boucle emballée ; il n'arbitre pas entre usagers derrière un
    # NAT partagé, où des centaines de personnes partagent une IP.
    requests-per-minute: 600
```

Vérifie qu'`app.ratelimit` est bien au même niveau d'indentation qu'`app.network` et `app.prim`.

- [ ] **Étape 2 : écrire le test des propriétés**

Crée `backend/src/test/java/com/mapidf/configurations/properties/RateLimitPropertiesTest.java` :

```java
package com.mapidf.configurations.properties;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RateLimitPropertiesTest {

    @Test
    void accepteUnBudgetPositif() {
        assertThat(new RateLimitProperties(600).requestsPerMinute()).isEqualTo(600);
    }

    @Test
    void refuseUnBudgetNulOuNegatif() {
        // Un budget à 0 refuserait tout le trafic en silence : mieux vaut ne pas démarrer que
        // servir des 429 à tout le monde parce qu'une variable d'environnement est vide.
        assertThatThrownBy(() -> new RateLimitProperties(0))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("app.ratelimit.requests-per-minute");

        assertThatThrownBy(() -> new RateLimitProperties(-1))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
```

- [ ] **Étape 3 : lancer et regarder échouer**

```bash
JAVA_HOME=~/.jdks/temurin-25.0.4 ./mvnw -q test -Dtest=RateLimitPropertiesTest
```

Attendu : **échec de compilation**, `cannot find symbol: class RateLimitProperties`.

- [ ] **Étape 4 : écrire `RateLimitProperties`**

Crée `backend/src/main/java/com/mapidf/configurations/properties/RateLimitProperties.java` :

```java
package com.mapidf.configurations.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Budget de requêtes par IP et par minute (SEC-3). Valeur littérale dans l'application.yml et
 * non variable du .env : c'est un réglage fonctionnel, pas une coordonnée d'infrastructure, et
 * l'inscrire au .env.example la rendrait obligatoire au démarrage (ConfigurationGuard).
 */
@ConfigurationProperties(prefix = "app.ratelimit")
public record RateLimitProperties(int requestsPerMinute) {

    public RateLimitProperties {
        if (requestsPerMinute <= 0) {
            throw new IllegalArgumentException(
                "app.ratelimit.requests-per-minute doit être > 0 (reçu : " + requestsPerMinute + ")");
        }
    }
}
```

- [ ] **Étape 5 : lancer et voir passer**

```bash
JAVA_HOME=~/.jdks/temurin-25.0.4 ./mvnw -q test -Dtest=RateLimitPropertiesTest
```

Attendu : `BUILD SUCCESS`, 2 tests verts.

- [ ] **Étape 6 : ajouter la valeur d'énumération `TOO_MANY_REQUESTS`**

Dans `backend/src/main/java/com/mapidf/data/enums/ErrorCode.java`, ajoute la valeur **après `BAD_REQUEST`** et avant `INTERNAL_ERROR` (les erreurs client d'abord, comme aujourd'hui) :

```java
    STATION_NOT_FOUND("Station not found"),
    BAD_REQUEST("Invalid request"),
    TOO_MANY_REQUESTS("Too many requests"),
    INTERNAL_ERROR("Internal server error");
```

- [ ] **Étape 7 : écrire l'IT du quota, qui échouera**

Crée `backend/src/test/java/com/mapidf/controllers/support/RateLimitIT.java` :

```java
package com.mapidf.controllers.support;

import com.mapidf.MapIdfTest;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Budget ramené à 3 par @TestPropertySource : la propriété diffère de celle des autres IT, donc
 * Spring construit un contexte distinct et ce fichier n'a aucun effet sur eux.
 */
@MapIdfTest
@TestPropertySource(properties = "app.ratelimit.requests-per-minute=3")
class RateLimitIT {

    private static final int BUDGET = 3;

    @Autowired WebApplicationContext wac;
    @Autowired MeterRegistry meters;
    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).build();
    }

    /**
     * Le RateLimiter est un champ du bean interceptor, donc partagé par toutes les méthodes de
     * ce contexte : chaque test prend une IP distincte pour ne pas hériter du compteur du
     * précédent. Adresses de RFC 5737 (TEST-NET-3), jamais routées.
     */
    private ResultActions call(String ip) throws Exception {
        return mockMvc.perform(get("/network").with(request -> {
            request.setRemoteAddr(ip);
            return request;
        }));
    }

    @Test
    void refuseAuDelaDuBudgetAvecUn429Complet() throws Exception {
        for (int i = 0; i < BUDGET; i++) {
            call("203.0.113.7").andExpect(status().isOk());
        }

        call("203.0.113.7")
            .andExpect(status().isTooManyRequests())
            .andExpect(header().exists("Retry-After"))
            .andExpect(jsonPath("$.status").value(429))
            .andExpect(jsonPath("$.errorCode").value("TOO_MANY_REQUESTS"))
            .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void leRetryAfterEstUnNombreDeSecondesExploitable() throws Exception {
        for (int i = 0; i < BUDGET; i++) {
            call("203.0.113.8");
        }

        String retryAfter = call("203.0.113.8")
            .andExpect(status().isTooManyRequests())
            .andReturn().getResponse().getHeader("Retry-After");

        assertThat(retryAfter).isNotNull();
        assertThat(Long.parseLong(retryAfter)).isBetween(1L, 60L);
    }

    @Test
    void compteLeRejet() throws Exception {
        double avant = meters.counter("mapidf.ratelimit.rejected").count();

        for (int i = 0; i <= BUDGET; i++) {
            call("203.0.113.9");
        }

        assertThat(meters.counter("mapidf.ratelimit.rejected").count()).isEqualTo(avant + 1);
    }

    @Test
    void neRefuseJamaisLaLoopback() throws Exception {
        // C'est aussi ce qui protège les IT existants : ils passent tous par MockMvc, donc par
        // remoteAddr = 127.0.0.1, dans un contexte Spring mis en cache entre classes de test.
        for (int i = 0; i < BUDGET * 4; i++) {
            call("127.0.0.1").andExpect(status().isOk());
        }
    }
}
```

- [ ] **Étape 8 : lancer l'IT et le regarder échouer**

```bash
JAVA_HOME=~/.jdks/temurin-25.0.4 ./mvnw -q verify -Dit.test=RateLimitIT -DfailIfNoTests=false
```

`-Dit.test` est la propriété du plugin failsafe : seul `RateLimitIT` tourne côté intégration. Surefire, lui, rejoue tous les tests unitaires — c'est rapide et ça ne gêne pas.

Attendu : **trois méthodes sur quatre échouent** (`200` au lieu de `429`, en-tête `Retry-After` absent, compteur inchangé) ; `neRefuseJamaisLaLoopback` passe déjà, puisque rien ne refuse encore quoi que ce soit. C'est le rouge recherché : l'interceptor n'existe pas.

- [ ] **Étape 9 : écrire l'interceptor et son enregistrement**

Crée `backend/src/main/java/com/mapidf/controllers/support/RateLimitInterceptor.java` :

```java
package com.mapidf.controllers.support;

import java.time.Clock;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.mapidf.configurations.properties.RateLimitProperties;
import com.mapidf.data.enums.ErrorCode;
import com.mapidf.exceptions.ApiException;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Traduit un refus du {@link RateLimiter} en 429.
 *
 * <p>Interceptor et non Filter : une exception levée dans un Filter se produit hors du
 * DispatcherServlet, donc l'ApiExceptionHandler ne la voit pas et il faudrait réécrire à la main
 * la sérialisation d'ErrorResponse — une seconde implémentation du format d'erreur, vouée à
 * diverger de la première.
 *
 * <p>Conséquence à connaître : un chemin non mappé n'a pas de handler, donc n'appelle pas cet
 * interceptor, donc n'est pas compté. Sans importance — un 404 ne coûte rien.
 */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private final RateLimiter limiter;

    public RateLimitInterceptor(Clock clock, RateLimitProperties properties, MeterRegistry meters) {
        this.limiter = new RateLimiter(clock, properties.requestsPerMinute(), meters);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String ip = request.getRemoteAddr();
        if (ip == null || isLoopback(ip)) {
            return true;
        }

        RateLimiter.Decision decision = limiter.check(ip);
        if (decision.allowed()) {
            return true;
        }

        // Posé AVANT de lever : l'ApiExceptionHandler n'appelle que setStatus et setContentType,
        // donc la réponse n'est pas encore validée et l'en-tête survit.
        response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(decision.retryAfterSeconds()));
        throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, ErrorCode.TOO_MANY_REQUESTS);
    }

    /**
     * 127.0.0.0/8 et ::1 : après résolution du X-Forwarded-For, c'est la machine elle-même et
     * jamais un client public. Pas InetAddress.getByName — sur autre chose qu'un littéral, il
     * ferait une résolution DNS à chaque requête.
     */
    private static boolean isLoopback(String ip) {
        return ip.startsWith("127.") || "::1".equals(ip) || "0:0:0:0:0:0:0:1".equals(ip);
    }
}
```

Crée `backend/src/main/java/com/mapidf/configurations/WebMvcConfiguration.java` :

```java
package com.mapidf.configurations;

import com.mapidf.controllers.support.RateLimitInterceptor;
import lombok.AllArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Sans motif de chemin : l'interceptor couvre les quatre endpoints. Il n'atteint pas l'Actuator,
 * qui vit sur le port 9100 dans un contexte enfant distinct — et ce port n'est publié que sur la
 * loopback.
 */
@Configuration
@AllArgsConstructor
public class WebMvcConfiguration implements WebMvcConfigurer {

    private final RateLimitInterceptor rateLimitInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(rateLimitInterceptor);
    }
}
```

- [ ] **Étape 10 : lancer l'IT et le voir passer**

```bash
JAVA_HOME=~/.jdks/temurin-25.0.4 ./mvnw -q verify -Dit.test=RateLimitIT -DfailIfNoTests=false
```

Attendu : `BUILD SUCCESS`, 4 tests verts côté intégration.

- [ ] **Étape 11 : vérifier la suite entière**

C'est le contrôle qui compte : l'exemption loopback doit laisser les 53 IT existants intacts, et rien ne doit avoir bougé côté unitaire.

```bash
JAVA_HOME=~/.jdks/temurin-25.0.4 ./mvnw verify
```

Attendu : `BUILD SUCCESS`, **plus** de tests qu'avant (référence du 2026-08-13 : 167 exécutions unitaires + 53 IT), zéro échec, zéro erreur. Si un IT existant rougit en 429, l'exemption loopback ne fonctionne pas — c'est `isLoopback` ou l'ordre des tests qu'il faut regarder, pas le budget qu'il faut relever.

- [ ] **Étape 12 : commit**

```bash
git add backend/src/main/java/com/mapidf/configurations/properties/RateLimitProperties.java \
        backend/src/test/java/com/mapidf/configurations/properties/RateLimitPropertiesTest.java \
        backend/src/main/java/com/mapidf/controllers/support/RateLimitInterceptor.java \
        backend/src/main/java/com/mapidf/configurations/WebMvcConfiguration.java \
        backend/src/test/java/com/mapidf/controllers/support/RateLimitIT.java \
        backend/src/main/java/com/mapidf/data/enums/ErrorCode.java \
        backend/src/main/resources/application.yml
git commit -m "$(cat <<'EOF'
feat(sec-3): 429 au-delà de 600 requêtes par IP et par minute

Interceptor et non Filter : une exception levée dans un Filter passe hors du
DispatcherServlet, donc l'ApiExceptionHandler ne la voit pas — il faudrait
réécrire le format d'erreur une seconde fois.

forward-headers-strategy: native, et pas framework : seul le RemoteIpValve de
Tomcat a une notion de proxy de confiance, donc seul lui empêche un client
public de forger son IP dans X-Forwarded-For.

Loopback exempté : c'est la machine elle-même dans les trois topologies
visées, et ça laisse les IT existants (tous en remoteAddr 127.0.0.1, dans un
contexte partagé) hors du compteur.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 3 : second rideau nginx et fermeture du port 8100

Deux changements d'infrastructure qu'**aucun test automatisé ne peut couvrir** : ils ne se constatent que sur la pile lancée. Cette tâche produit la configuration et la vérifie autant que possible hors pile ; la recette est remise à l'utilisateur.

**Files:**
- Modify: `frontend/nginx.conf`
- Modify: `docker-compose.yml`

**Interfaces:** aucune — rien dans le code Java ne dépend de cette tâche, et réciproquement. Le backend reste la référence du quota ; nginx est un second rideau qui ne déclenchera pas au même instant (seau percé contre fenêtre fixe), ce qui est attendu.

---

- [ ] **Étape 1 : déclarer la zone au niveau `http`**

Dans `frontend/nginx.conf`, à la suite du bloc `map` et **avant** `server {` :

```nginx
# limit_req_zone n'est valide qu'au niveau `http` — ce fichier étant inclus depuis conf.d/, on y
# est. 10 Mo tiennent de l'ordre de 160 000 adresses.
#
# Second rideau seulement : le quota qui fait foi est dans le backend, seul point présent dans
# les trois topologies visées (compose, Ingress Kubernetes, conteneurs serverless — cf. spec
# SEC-3 § 2.1). Ici, 10 r/s est le même ordre de grandeur que ses 600/min, mais l'algorithme
# diffère (seau percé contre fenêtre fixe) : les deux ne déclencheront pas au même instant, et
# c'est sans conséquence.
limit_req_zone $binary_remote_addr zone=api:10m rate=10r/s;
```

- [ ] **Étape 2 : appliquer la limite au seul `location /api/`**

Dans le bloc `location /api/ {`, juste après l'`include` des en-têtes de sécurité :

```nginx
    # Pas sur /assets/ ni sur / : les fichiers statiques coûtent peu et sont déjà cachés un an,
    # les limiter ne protégerait rien et gênerait un rechargement forcé.
    limit_req zone=api burst=60 nodelay;
    # nginx répond 503 par défaut, ce qui dirait « le service est en panne » au lieu de « tu vas
    # trop vite ».
    limit_req_status 429;
```

- [ ] **Étape 3 : valider la syntaxe nginx dans un conteneur jetable**

Ce `docker run --rm` ne touche à aucun conteneur existant : il démarre une image, teste la configuration, et s'efface.

```bash
cd /home/abodet/workspace/perso/MapIDF
docker run --rm \
  -v "$PWD/frontend/nginx.conf:/etc/nginx/conf.d/default.conf:ro" \
  -v "$PWD/frontend/security-headers.conf:/etc/nginx/security-headers.conf:ro" \
  nginxinc/nginx-unprivileged:alpine nginx -t
```

Attendu : `syntax is ok` puis `test is successful`. Une erreur `"limit_req_zone" directive is not allowed here` signifie que la zone a atterri dans le bloc `server` au lieu du niveau fichier.

Si Docker n'est pas disponible sur la machine, **ne bloque pas** : note-le dans ton rapport et laisse la validation à la recette de l'étape 6.

- [ ] **Étape 4 : refermer le port 8100 sur la loopback**

Dans `docker-compose.yml`, service `backend`, remplace la ligne `ports:` par :

```yaml
    # Les DEUX ports sur la loopback : l'Actuator l'était déjà, l'API le devient. nginx joint le
    # backend par le réseau du compose (`backend:8100`), il n'a jamais eu besoin d'un port publié
    # — qui n'était qu'un contournement du rideau nginx offert à tout le réseau local.
    # Le backend reste joignable depuis la machine (curl, IntelliJ, scripts).
    ports: ["127.0.0.1:${SERVER_PORT:-8100}:8100", "127.0.0.1:${MANAGEMENT_SERVER_PORT:-9100}:9100"]
```

Le commentaire de trois lignes qui précédait la ligne `ports:` (sur l'Actuator et la loopback) est remplacé par celui-ci : il disait déjà l'essentiel pour un seul des deux ports, le nouveau couvre les deux.

- [ ] **Étape 5 : vérifier que rien de compilé n'a bougé**

```bash
JAVA_HOME=~/.jdks/temurin-25.0.4 ./mvnw -q verify
```

Attendu : `BUILD SUCCESS`, mêmes compteurs qu'à la fin de la Task 2. Cette tâche ne touche pas au Java ; le lancer sert à écarter un doute, pas à découvrir quelque chose.

- [ ] **Étape 6 : rédiger la recette à remettre à l'utilisateur**

**Ne lance pas la pile toi-même** — l'utilisateur la pilote. Écris dans ton rapport de tâche, mot pour mot et prêt à copier, la recette suivante :

````markdown
## Recette SEC-3 (à jouer sur la pile Docker relancée)

```bash
# 1. Le second rideau nginx : 200 requêtes d'affilée, au moins une doit repartir en 429.
seq 200 | xargs -I{} -P1 curl -s -o /dev/null -w "%{http_code}\n" \
  http://localhost:8080/api/network | sort | uniq -c
# Attendu : une majorité de 200, et un nombre non nul de 429.
# (~61 requêtes passent : burst de 60 plus la recharge à 10 r/s.)

# 2. Le port 8100 n'est plus publié hors de la machine.
docker compose ps --format '{{.Service}}\t{{.Ports}}' | grep backend
# Attendu : 127.0.0.1:8100->8100/tcp — et non 0.0.0.0:8100->8100/tcp.

# 3. Les en-têtes de sécurité n'ont pas bougé (SEC-4).
#    À lancer APRÈS avoir laissé retomber le seau du point 1 : attends ~10 s.
scripts/check-headers.sh

# 4. Le quota backend voit la vraie IP cliente, pas celle de nginx.
#    Ce point ne se vérifie QUE sur un vrai connecteur : MockMvc ne monte pas le RemoteIpValve.
curl -s -o /dev/null -w "%{http_code}\n" \
  -H 'X-Forwarded-For: 203.0.113.42' http://localhost:8080/api/network
# Attendu : 200. Puis, dans les logs du backend, aucun WARN « Quota dépassé ».
# Puis 700 requêtes avec ce même en-tête forgé :
seq 700 | xargs -I{} -P1 curl -s -o /dev/null \
  -H 'X-Forwarded-For: 203.0.113.42' http://localhost:8080/api/network
# Attendu dans les logs backend : exactement UN WARN
#   « Quota dépassé pour 203.0.113.42 : plus de 600 requêtes sur la minute … »
# Si l'IP journalisée est celle du conteneur nginx (172.x), forward-headers-strategy
# n'est pas pris en compte.
# (nginx en refusera une partie avant le backend : c'est attendu, relance si besoin.)

# 5. La métrique existe.
curl -s http://localhost:9100/actuator/prometheus | grep mapidf_ratelimit_rejected
# Attendu : une ligne mapidf_ratelimit_rejected_total avec une valeur > 0.
```
````

- [ ] **Étape 7 : commit**

```bash
git add frontend/nginx.conf docker-compose.yml
git commit -m "$(cat <<'EOF'
feat(sec-3): second rideau limit_req dans nginx, port 8100 sur la loopback

limit_req_zone au niveau http (nginx le refuse ailleurs), limit_req dans le
seul location /api/, et limit_req_status 429 — nginx répond 503 par défaut,
ce qui annoncerait une panne au lieu d'un excès de vitesse.

Le port 8100 était publié sur toutes les interfaces alors que l'Actuator
juste en dessous était déjà restreint : c'était un contournement du rideau
nginx offert au réseau local.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 4 : documentation

Ce que le chantier a appris doit survivre à la session. Trois fichiers, trois publics différents.

**Files:**
- Modify: `docs/roadmap.md`
- Modify: `CLAUDE.md`
- Modify: `README.md`

**Interfaces:** aucune.

---

- [ ] **Étape 1 : clore SEC-3 dans la roadmap**

Dans `docs/roadmap.md`, ligne `| SEC-3 | Rate limiting | …`, remplace la colonne Statut (aujourd'hui « à faire — **a hérité du micro-cache nginx**… ») par un texte qui dit :

- **fait**, avec un lien vers `superpowers/specs/2026-08-13-sec-3-rate-limiting-design.md` ;
- que la fiche se trompait sur la **couche** : le quota porteur est dans le backend et non dans nginx, parce que `frontend/nginx.conf` n'est pas sur le chemin de `/api` sur Kubernetes avec Ingress ni sur des conteneurs serverless — un quota écrit là disparaîtrait au déploiement sans que rien ne le signale ;
- ce qui est livré : `RateLimiter` à fenêtre fixe d'une minute, 600 req/min par IP, `HandlerInterceptor` rendant un `429` au format `ErrorResponse` du projet avec `Retry-After`, `mapidf.ratelimit.rejected`, un WARN borné à un par IP et par fenêtre, `limit_req` en second rideau dans nginx, et le port 8100 refermé sur la loopback ;
- que **`/network` était le vrai trou** : PERF-3 l'avait écarté à raison (un appel par client), mais il resérialise 8 110 points à chaque appel sans cache serveur, et son `Cache-Control: max-age=600` est côté client donc inopérant contre une boucle ;
- que le **micro-cache nginx sort du périmètre**, avec le chiffre : 1,65 ms par hit mesuré par PERF-3, soit ~1,6 % d'un cœur à 40 clients, et absent du chemin hors compose. Le `no-store` n'était pas le blocage — `proxy_ignore_headers Cache-Control` le résout proprement ;
- les **limites assumées** : compteur en mémoire, donc quota × nombre de réplicas (relève de PERF-6) ; l'IP est une clé imparfaite derrière un NAT partagé ; et **le volet nginx n'a aucun garde-fou automatique**, il se vérifie à la recette.

Puis, dans « Ordre recommandé », réécris le point 3 : SEC-3 en sort (il est fait) et il ne reste que **SEC-6** (scan de dépendances) et ce qui attend une décision d'hébergeur. Vérifie au passage que la ligne **SEC-4** ne renvoie plus à un micro-cache attendu de SEC-3.

- [ ] **Étape 2 : les deux pièges dans CLAUDE.md**

Dans `CLAUDE.md`, section « Conventions de code », ajoute deux puces dans le style des existantes — factuelles, avec la conséquence mesurable, sans effet d'annonce :

1. **`server.forward-headers-strategy: native`, jamais `framework`.** `framework` installe le `ForwardedHeaderFilter` de Spring, qui n'a **pas** de notion de proxy de confiance : il croit `X-Forwarded-For`, donc n'importe quel client choisit l'IP sur laquelle le quota SEC-3 le compte. `native` installe le `RemoteIpValve` de Tomcat, dont le défaut `server.tomcat.remoteip.internal-proxies` (vérifié dans `spring-boot-tomcat-4.1.0.jar`) ne croit l'en-tête que d'une adresse privée. Et **aucun test ne le voit** : MockMvc ne monte pas la valve, seule une pile lancée le constate.
2. **Un `Filter` ne peut pas réutiliser l'`ApiExceptionHandler`.** Une exception levée dans un `jakarta.servlet.Filter` se produit hors du `DispatcherServlet` : l'`@RestControllerAdvice` ne la voit pas, et il faudrait réécrire à la main statut, `Content-Type` et sérialisation d'`ErrorResponse`. D'où le `HandlerInterceptor` de `RateLimitInterceptor`. Corollaire : un chemin **non mappé** n'a pas de handler, donc n'est pas compté par le quota.

Ajoute aussi, dans la section « Configuration du réseau suivi » ou juste après, une ligne sur le budget : **600 req/min par IP, `app.ratelimit.requests-per-minute`, littéral dans l'`application.yml` et pas dans `.env`** — c'est un réglage fonctionnel, et l'inscrire au `.env.example` le rendrait obligatoire au démarrage. La règle qui gouverne le chiffre : **ce quota arrête une boucle emballée, il n'arbitre pas entre usagers** derrière un NAT partagé. La loopback en est exemptée.

- [ ] **Étape 3 : le comportement public dans le README**

Dans `README.md` :

- section **« API »**, après la liste des quatre endpoints, ajoute un paragraphe : les endpoints sont limités à **600 requêtes par minute et par adresse IP** (`app.ratelimit.requests-per-minute`) ; au-delà la réponse est un **`429`** portant `Retry-After` et le corps d'erreur habituel ; la loopback n'est pas comptée ; un client normal consomme ~31 req/min par onglet, donc ne le voit jamais.
- section **« Ports : rien à configurer »**, corrige la description : dans la pile Docker, **les deux ports** (8100 et 9100) ne sont désormais publiés que sur `127.0.0.1`. Le paragraphe existant sur `SERVER_PORT` / `MANAGEMENT_SERVER_PORT` reste vrai — ces variables continuent de choisir le port d'hôte —, seule l'interface d'écoute change.

- [ ] **Étape 4 : relire les trois fichiers**

Relis les diffs. Aucun chiffre inventé : 600 req/min, ~31 req/min par onglet, 1,65 ms par hit, ~1,6 % d'un cœur à 40 clients, 8 110 points — tous viennent de la spec SEC-3 ou de celle de PERF-3. Si tu n'as pas la source d'un chiffre, retire-le plutôt que de l'approximer.

- [ ] **Étape 5 : dernière vérification complète**

```bash
JAVA_HOME=~/.jdks/temurin-25.0.4 ./mvnw verify
```

Attendu : `BUILD SUCCESS`, zéro échec, zéro erreur. Note les compteurs exacts (exécutions unitaires et IT) dans ton rapport : ils servent de référence au prochain chantier.

- [ ] **Étape 6 : commit**

```bash
git add docs/roadmap.md CLAUDE.md README.md
git commit -m "$(cat <<'EOF'
docs(sec-3): clôture du chantier, et les deux pièges qu'il a trouvés

La fiche roadmap se trompait de couche : nginx n'est pas sur le chemin de
/api hors compose. Le micro-cache qu'elle léguait à SEC-3 en sort, chiffre à
l'appui.

CLAUDE.md gagne native/framework et l'impossibilité pour un Filter de
réutiliser l'ApiExceptionHandler — deux pièges qu'aucun test ne voit.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Ce que ce plan ne couvre pas

Écrit ici pour qu'on ne le cherche pas ailleurs, et pour qu'aucune tâche ne l'ajoute de sa propre initiative :

- **Le micro-cache nginx** (spec § 7). Décision prise, chiffrée, à rouvrir seulement sur une mesure de production.
- **Le partage du compteur entre réplicas.** À N pods, le quota réel est N × 600. C'est PERF-6 qui porte le mono-instance implicite, avec le poller et le snapshot.
- **Le front.** Aucun changement : un 429 emprunte le chemin d'échec d'UX-1 et les pollers réessaient à cadence fixe, donc sans amplification.
- **Un garde-fou automatique du volet nginx.** La spec (§ 8) a tranché : la recette, pas un test. Ne pas ajouter de sonde de charge à `scripts/check-headers.sh`, dont ce n'est pas l'objet et qui deviendrait instable (son propre trafic ferait tomber le seau).
- **Tout quota par identité, par ligne ou par jeton.** Il n'y a pas d'identité dans cette API.
