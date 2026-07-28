# Durcissement + refactor perf du rendu — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Durcir l'ingestion temps réel et le rendu (correctifs de robustesse + fuites), puis refondre la boucle `requestAnimationFrame` de `VehicleLayer` (throttle + idle + feature-state + culling) — prérequis avant le multi-ligne.

**Architecture:** Deux phases dans un seul plan. **Phase 1 (Lot 1)** = correctifs isolés backend (timeouts, isolation du parse, `stopKey`, repli `journeyRef`, cache `LineSchedule`, requête arrêts distincts, index) et frontend (annulation des fetch, nettoyage des couches/handlers, garde NaN, mineurs). **Phase 2 (Lot 2)** = réécriture de la boucle de rendu de `VehicleLayer` par-dessus un Lot 1 terminé. Aucun contrat d'API ne change.

**Tech Stack:** Spring Boot 4.1 / Java 25 / Lombok / Jackson 3 (`tools.jackson.databind`) / PostGIS / JTS / Flyway / Testcontainers (backend) ; React 18 / TypeScript / MapLibre GL (frontend).

## Global Constraints

- **Backend build/tests** : `cd backend && JAVA_HOME=/home/abodet/.jdks/temurin-25.0.3 ./mvnw verify` (le wrapper prend Java 21 par défaut — `JAVA_HOME` est OBLIGATOIRE). `./mvnw test` pour les unitaires seuls.
- **Frontend** : PAS de tests unitaires (convention projet). Vérif = `cd frontend && npm run build` + contrôle visuel utilisateur.
- **Jackson 3** : sur un `JsonNode`, utiliser `.asString(...)` (jamais `.asText()`).
- **Ne jamais masquer un train** (décision produit ferme) : aucun seuil d'ETA.
- **Aucun changement de contrat d'API** : `/shape`, `/vehicles`, `/departures` inchangés.
- **Ne PAS démarrer/arrêter les apps** (l'utilisateur gère backend/front/Docker via son IDE). Seuls `./mvnw`/tests et `npm run build` sont autorisés pour la vérif.
- Commits en français, préfixe conventionnel, trailer `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`.
- Spec de référence : `docs/superpowers/specs/2026-07-28-durcissement-et-perf-rendu-design.md`.

---

## PHASE 1 — Lot 1 (durcissement)

### Task 1 : Isolation des courses au parse SIRI

**Files:**
- Modify: `backend/src/main/java/com/mapidf/rt/RealtimePoller.java` (méthode `parse`, ~L133-143)
- Test: `backend/src/test/java/com/mapidf/rt/RealtimePollerParseTest.java`

**Interfaces:**
- Consumes: `RealtimePoller.parse(ObjectMapper, byte[], Instant)` (statique, existant).
- Produces: comportement — une course dont un `EstimatedCall` a un horodatage illisible est ignorée (loggée), les autres courses du même flux sont conservées.

- [ ] **Step 1 : Écrire le test qui échoue**

Ajouter dans `RealtimePollerParseTest` :

```java
@Test
void skipsMalformedJourneyButKeepsValidOnes() {
    String json = """
        {"Siri":{"ServiceDelivery":{"EstimatedTimetableDelivery":[{"EstimatedJourneyVersionFrame":[{
          "EstimatedVehicleJourney":[
            {"LineRef":{"value":"L"},"DirectionRef":{"value":"Aller"},
             "DatedVehicleJourneyRef":{"value":"BON"},"DestinationName":[{"value":"Terminus"}],
             "EstimatedCalls":{"EstimatedCall":[
               {"StopPointRef":{"value":"STIF:StopPoint:Q:111:"},"ExpectedArrivalTime":"2026-07-28T09:00:00.000Z","DepartureStatus":"ON_TIME"}]}},
            {"LineRef":{"value":"L"},"DirectionRef":{"value":"Aller"},
             "DatedVehicleJourneyRef":{"value":"POURRI"},"DestinationName":[{"value":"Terminus"}],
             "EstimatedCalls":{"EstimatedCall":[
               {"StopPointRef":{"value":"STIF:StopPoint:Q:222:"},"ExpectedArrivalTime":"pas-une-date","DepartureStatus":"ON_TIME"}]}}
          ]}]}]}}}
        """;
    RtSnapshot snapshot = RealtimePoller.parse(new ObjectMapper(), json.getBytes(StandardCharsets.UTF_8), Instant.now());
    List<RtSnapshot.LiveJourney> journeys = snapshot.forLine("L");
    assertThat(journeys).hasSize(1);
    assertThat(journeys.getFirst().journeyRef()).isEqualTo("BON");
}
```

Ajouter les imports nécessaires en tête si absents : `import java.nio.charset.StandardCharsets;`, `import java.time.Instant;`, `import java.util.List;`, `import static org.assertj.core.api.Assertions.assertThat;`, `import tools.jackson.databind.ObjectMapper;`, `import com.mapidf.rt.RtSnapshot;`.

- [ ] **Step 2 : Lancer le test → il échoue**

Run: `cd backend && JAVA_HOME=/home/abodet/.jdks/temurin-25.0.3 ./mvnw test -Dtest=RealtimePollerParseTest#skipsMalformedJourneyButKeepsValidOnes`
Expected: FAIL — `Instant.parse("pas-une-date")` lève une `DateTimeParseException` qui remonte hors de `parse`.

- [ ] **Step 3 : Envelopper `toJourney` par course**

Dans `parse`, remplacer la boucle interne :

```java
                for (JsonNode journey : frame.path("EstimatedVehicleJourney")) {
                    RtSnapshot.LiveJourney live = toJourney(journey);
                    if (live != null) {
                        byLine.computeIfAbsent(live.lineRef(), key -> new ArrayList<>()).add(live);
                    }
                }
```

par :

```java
                for (JsonNode journey : frame.path("EstimatedVehicleJourney")) {
                    try {
                        RtSnapshot.LiveJourney live = toJourney(journey);
                        if (live != null) {
                            byLine.computeIfAbsent(live.lineRef(), key -> new ArrayList<>()).add(live);
                        }
                    } catch (RuntimeException e) {
                        // Une course pourrie (horodatage illisible, structure inattendue) ne doit pas
                        // faire perdre tout le snapshot — surtout en réseau complet (multi-ligne).
                        log.warn("[RT] Course ignorée (parse impossible): {}", e.getMessage());
                    }
                }
```

- [ ] **Step 4 : Lancer le test → il passe**

Run: `cd backend && JAVA_HOME=/home/abodet/.jdks/temurin-25.0.3 ./mvnw test -Dtest=RealtimePollerParseTest`
Expected: PASS (tous les tests de la classe).

- [ ] **Step 5 : Commit**

```bash
git add backend/src/main/java/com/mapidf/rt/RealtimePoller.java backend/src/test/java/com/mapidf/rt/RealtimePollerParseTest.java
git commit -m "fix(back): isoler le parse par course (une course pourrie n'annule plus le snapshot)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 2 : `stopKey` robuste (dernier groupe de chiffres)

**Files:**
- Modify: `backend/src/main/java/com/mapidf/position/PositionEngine.java` (`stopKey`, L144-146)
- Test: `backend/src/test/java/com/mapidf/position/PositionEngineTest.java`

**Interfaces:**
- Produces: `PositionEngine.stopKey(String)` renvoie le **dernier** groupe de chiffres de l'id (`""` si aucun / null).

- [ ] **Step 1 : Écrire le test qui échoue**

Ajouter dans `PositionEngineTest` :

```java
@Test
void stopKeyExtractsLastNumericGroup() {
    assertThat(PositionEngine.stopKey("STIF:StopPoint:Q:463221:")).isEqualTo("463221");
    assertThat(PositionEngine.stopKey("IDFM:463221")).isEqualTo("463221");
    assertThat(PositionEngine.stopKey("IDFM:StopPoint:59:463221")).isEqualTo("463221");
    assertThat(PositionEngine.stopKey(null)).isEmpty();
    assertThat(PositionEngine.stopKey("aucun-chiffre")).isEmpty();
}
```

(Import `static org.assertj.core.api.Assertions.assertThat;` si absent.)

- [ ] **Step 2 : Lancer le test → il échoue**

Run: `cd backend && JAVA_HOME=/home/abodet/.jdks/temurin-25.0.3 ./mvnw test -Dtest=PositionEngineTest#stopKeyExtractsLastNumericGroup`
Expected: FAIL — l'implémentation actuelle (`replaceAll("\\D","")`) renvoie `"59463221"` pour le 3ᵉ cas.

- [ ] **Step 3 : Extraire le dernier groupe `\d+`**

Remplacer :

```java
    public static String stopKey(String rawRef) {
        return rawRef == null ? "" : rawRef.replaceAll("\\D", "");
    }
```

par :

```java
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
```

- [ ] **Step 4 : Lancer les tests → ils passent**

Run: `cd backend && JAVA_HOME=/home/abodet/.jdks/temurin-25.0.3 ./mvnw test -Dtest=PositionEngineTest`
Expected: PASS (le nouveau test + tous les existants — les ids ligne 9 donnent le même résultat qu'avant).

- [ ] **Step 5 : Commit**

```bash
git add backend/src/main/java/com/mapidf/position/PositionEngine.java backend/src/test/java/com/mapidf/position/PositionEngineTest.java
git commit -m "fix(back): stopKey extrait le dernier groupe de chiffres (robustesse ids)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 3 : Repli `journeyRef` sans collision

**Files:**
- Modify: `backend/src/main/java/com/mapidf/rt/RealtimePoller.java` (`toJourney`, L149-169)
- Test: `backend/src/test/java/com/mapidf/rt/RealtimePollerParseTest.java`

**Interfaces:**
- Produces: quand `DatedVehicleJourneyRef` est absent, `journeyRef` = `lineRef|directionRef|destination|<heure du 1ᵉʳ appel>` (identité composite), plus le seul `stopRef`.

- [ ] **Step 1 : Écrire le test qui échoue**

Ajouter dans `RealtimePollerParseTest` :

```java
@Test
void fallsBackToCompositeJourneyRefWhenDatedRefMissing() {
    String json = """
        {"Siri":{"ServiceDelivery":{"EstimatedTimetableDelivery":[{"EstimatedJourneyVersionFrame":[{
          "EstimatedVehicleJourney":[
            {"LineRef":{"value":"L"},"DirectionRef":{"value":"Aller"},"DestinationName":[{"value":"A"}],
             "EstimatedCalls":{"EstimatedCall":[
               {"StopPointRef":{"value":"STIF:StopPoint:Q:111:"},"ExpectedArrivalTime":"2026-07-28T09:00:00.000Z"}]}},
            {"LineRef":{"value":"L"},"DirectionRef":{"value":"Retour"},"DestinationName":[{"value":"B"}],
             "EstimatedCalls":{"EstimatedCall":[
               {"StopPointRef":{"value":"STIF:StopPoint:Q:111:"},"ExpectedArrivalTime":"2026-07-28T09:00:00.000Z"}]}}
          ]}]}]}}}
        """;
    RtSnapshot snapshot = RealtimePoller.parse(new ObjectMapper(), json.getBytes(StandardCharsets.UTF_8), Instant.now());
    List<RtSnapshot.LiveJourney> journeys = snapshot.forLine("L");
    assertThat(journeys).hasSize(2);
    assertThat(journeys.get(0).journeyRef()).isNotEqualTo(journeys.get(1).journeyRef());
}
```

- [ ] **Step 2 : Lancer le test → il échoue**

Run: `cd backend && JAVA_HOME=/home/abodet/.jdks/temurin-25.0.3 ./mvnw test -Dtest=RealtimePollerParseTest#fallsBackToCompositeJourneyRefWhenDatedRefMissing`
Expected: FAIL — les deux courses replient sur le même `stopRef` `STIF:StopPoint:Q:111:` → `journeyRef` identiques.

- [ ] **Step 3 : Replier sur une identité composite**

Dans `toJourney`, remplacer la fin :

```java
        String lineRef = journey.path("LineRef").path("value").asString("");
        String journeyRef = journey.path("DatedVehicleJourneyRef").path("value")
            .asString(calls.getFirst().stopRef());
        String directionRef = journey.path("DirectionRef").path("value").asString("");
        String destination = firstValue(journey.path("DestinationName"));
        return new RtSnapshot.LiveJourney(lineRef, journeyRef, directionRef, destination, calls);
```

par :

```java
        String lineRef = journey.path("LineRef").path("value").asString("");
        String directionRef = journey.path("DirectionRef").path("value").asString("");
        String destination = firstValue(journey.path("DestinationName"));
        // DatedVehicleJourneyRef est souvent absent : on replie sur une identité composite
        // (et non sur le seul stopRef, qui collisionnerait entre deux courses de sens opposés
        // partageant leur premier arrêt du flux non trié) → pas de fusion de trains côté front.
        String journeyRef = journey.path("DatedVehicleJourneyRef").path("value").asString(null);
        if (journeyRef == null) {
            journeyRef = lineRef + "|" + directionRef + "|" + destination + "|" + calls.getFirst().time();
        }
        return new RtSnapshot.LiveJourney(lineRef, journeyRef, directionRef, destination, calls);
```

- [ ] **Step 4 : Lancer les tests → ils passent**

Run: `cd backend && JAVA_HOME=/home/abodet/.jdks/temurin-25.0.3 ./mvnw test -Dtest=RealtimePollerParseTest`
Expected: PASS.

- [ ] **Step 5 : Commit**

```bash
git add backend/src/main/java/com/mapidf/rt/RealtimePoller.java backend/src/test/java/com/mapidf/rt/RealtimePollerParseTest.java
git commit -m "fix(back): repli journeyRef composite (plus de collision de tripId)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 4 : Timeouts HTTP + `fixedDelay`

**Files:**
- Modify: `backend/src/main/java/com/mapidf/rt/RealtimePoller.java` (client L49, `@Scheduled` L71, requête L109-114)
- Modify: `backend/src/main/java/com/mapidf/gtfs/GtfsStaticService.java` (client L24)

**Interfaces:**
- Produces: le poller ne peut plus pendre indéfiniment (connect + requête bornés) et ses exécutions ne se chevauchent plus (`fixedDelay`).

Cette tâche est un durcissement de configuration réseau : pas de test unitaire dédié (tester un timeout `HttpClient` sans réseau serait fragile et à faible valeur). Vérification = `./mvnw verify` reste vert + revue du diff.

- [ ] **Step 1 : Borner le client et la requête du poller**

Dans `RealtimePoller`, remplacer :

```java
    private final HttpClient httpClient = HttpClient.newHttpClient();
```

par :

```java
    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();
```

Puis dans `fetch`, remplacer la construction de la requête :

```java
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
            .header(prim.authHeader(), prim.apiKey())
            .GET()
            .build();
```

par :

```java
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
            .header(prim.authHeader(), prim.apiKey())
            .timeout(Duration.ofSeconds(10))  // bien < l'intervalle de poll (60 s)
            .GET()
            .build();
```

(`java.time.Duration` est déjà importé.)

- [ ] **Step 2 : Sérialiser les polls (`fixedDelay`)**

Remplacer l'annotation de `poll()` :

```java
    @Scheduled(fixedRateString = "${app.prim.poll-interval}")
```

par :

```java
    // fixedDelay : le prochain poll ne démarre qu'après la fin du précédent → pas de
    // chevauchement ni de rafale de connexions vers PRIM si un appel traîne.
    @Scheduled(fixedDelayString = "${app.prim.poll-interval}")
```

- [ ] **Step 3 : Borner le client de téléchargement GTFS**

Dans `GtfsStaticService`, remplacer :

```java
    private final HttpClient httpClient = HttpClient.newHttpClient();
```

par :

```java
    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(java.time.Duration.ofSeconds(15))
        .build();
```

(Pas de `.timeout` court sur la requête GTFS : le téléchargement ~109 Mo est légitimement long ; seul l'établissement de connexion est borné.)

- [ ] **Step 4 : Build + tests**

Run: `cd backend && JAVA_HOME=/home/abodet/.jdks/temurin-25.0.3 ./mvnw verify`
Expected: BUILD SUCCESS, tous les tests/IT verts.

- [ ] **Step 5 : Commit**

```bash
git add backend/src/main/java/com/mapidf/rt/RealtimePoller.java backend/src/main/java/com/mapidf/gtfs/GtfsStaticService.java
git commit -m "fix(back): timeouts HTTP + fixedDelay (plus de poll qui pend ou se chevauche)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 5 : Cache du `LineSchedule`

**Files:**
- Modify: `backend/src/main/java/com/mapidf/position/ScheduleProvider.java`
- Modify: `backend/src/main/java/com/mapidf/gtfs/GtfsStaticService.java` (invalidation dans `refresh`)
- Test: `backend/src/test/java/com/mapidf/position/ScheduleProviderTest.java` (créer)

**Interfaces:**
- Produces: `ScheduleProvider.getLineSchedule(LineString, String)` mémoïse par `gtfsRouteId` ; `ScheduleProvider.invalidate()` vide le cache.
- Consumes (GtfsStaticService) : `scheduleProvider.invalidate()`.

- [ ] **Step 1 : Écrire le test qui échoue**

Créer `backend/src/test/java/com/mapidf/position/ScheduleProviderTest.java` :

```java
package com.mapidf.position;

import java.util.List;

import com.mapidf.data.repositories.StopTimeRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScheduleProviderTest {

    @Mock
    StopTimeRepository stopTimeRepository;

    @InjectMocks
    ScheduleProvider provider;

    private static LineString line() {
        return new GeometryFactory().createLineString(
            new Coordinate[]{new Coordinate(2.0, 48.0), new Coordinate(2.1, 48.1)});
    }

    @Test
    void cachesScheduleUntilInvalidated() {
        when(stopTimeRepository.findScheduleByRouteGtfsId("R")).thenReturn(List.of());

        provider.getLineSchedule(line(), "R");
        provider.getLineSchedule(line(), "R");
        verify(stopTimeRepository, times(1)).findScheduleByRouteGtfsId("R");

        provider.invalidate();
        provider.getLineSchedule(line(), "R");
        verify(stopTimeRepository, times(2)).findScheduleByRouteGtfsId("R");
    }
}
```

- [ ] **Step 2 : Lancer le test → il échoue**

Run: `cd backend && JAVA_HOME=/home/abodet/.jdks/temurin-25.0.3 ./mvnw test -Dtest=ScheduleProviderTest`
Expected: FAIL — pas de cache aujourd'hui (2 appels au repo après la 1ʳᵉ vérif) et `invalidate()` n'existe pas (ne compile pas).

- [ ] **Step 3 : Ajouter le cache**

Dans `ScheduleProvider`, ajouter le champ cache et modifier `getLineSchedule` (renommer le corps de calcul en `computeSchedule`), ajouter `invalidate()`. Le `@Transactional(readOnly = true)` reste sur la méthode publique (transaction quasi gratuite sur un hit de cache, aucune requête) :

```java
    private final StopTimeRepository stopTimeRepository;
    private final java.util.Map<String, LineSchedule> cache = new java.util.concurrent.ConcurrentHashMap<>();

    @Transactional(readOnly = true)
    public LineSchedule getLineSchedule(LineString line, String gtfsRouteId) {
        LineSchedule cached = cache.get(gtfsRouteId);
        if (cached != null) {
            return cached;
        }
        LineSchedule computed = computeSchedule(line, gtfsRouteId);
        cache.put(gtfsRouteId, computed);
        return computed;
    }

    // Vidé au rechargement du GTFS (cf. GtfsStaticService.refresh) : l'horaire ne change
    // qu'à ce moment-là, inutile de le recalculer depuis la base à chaque poll /vehicles.
    public void invalidate() {
        cache.clear();
    }

    private LineSchedule computeSchedule(LineString line, String gtfsRouteId) {
        // ... corps ACTUEL de getLineSchedule, inchangé (LengthIndexedLine, regroupement
        //     par trip, course représentative par sens, construction des DirectionSchedule) ...
    }
```

Déplacer tel quel le corps existant de `getLineSchedule` dans `computeSchedule` (aucune modification de la logique interne).

- [ ] **Step 4 : Invalider au reload GTFS**

Dans `GtfsStaticService`, injecter `ScheduleProvider` et appeler `invalidate()` après le rechargement. Ajouter le champ + le paramètre de constructeur :

```java
    private final GtfsStaticLoader loader;
    private final RouteRepository routeRepository;
    private final PrimProperties prim;
    private final LineProperties line;
    private final ScheduleProvider scheduleProvider;
```

```java
    public GtfsStaticService(GtfsStaticLoader loader, RouteRepository routeRepository,
                             PrimProperties prim, LineProperties line, ScheduleProvider scheduleProvider) {
        this.loader = loader;
        this.routeRepository = routeRepository;
        this.prim = prim;
        this.line = line;
        this.scheduleProvider = scheduleProvider;
    }
```

Dans `refresh()`, après `loader.loadFromZip(...)` et `cacheGeometry()` :

```java
            loader.loadFromZip(response.body(), line.gtfsRouteId());
            cacheGeometry();
            scheduleProvider.invalidate();
            log.info("[GTFS] Réseau ligne {} rechargé", line.gtfsRouteId());
```

Ajouter l'import `import com.mapidf.position.ScheduleProvider;`.

- [ ] **Step 5 : Lancer les tests → ils passent**

Run: `cd backend && JAVA_HOME=/home/abodet/.jdks/temurin-25.0.3 ./mvnw test -Dtest=ScheduleProviderTest`
Expected: PASS.

- [ ] **Step 6 : Vérifier la non-régression complète (IT inclus)**

Run: `cd backend && JAVA_HOME=/home/abodet/.jdks/temurin-25.0.3 ./mvnw verify`
Expected: BUILD SUCCESS — `LineControllerVehiclesIT` et les autres IT valident que `/vehicles` reste correct avec le cache.

- [ ] **Step 7 : Commit**

```bash
git add backend/src/main/java/com/mapidf/position/ScheduleProvider.java backend/src/main/java/com/mapidf/gtfs/GtfsStaticService.java backend/src/test/java/com/mapidf/position/ScheduleProviderTest.java
git commit -m "perf(back): cache du LineSchedule (invalidé au reload GTFS)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 6 : Requête arrêts distincts pour `/shape` + index (migration V3)

**Files:**
- Modify: `backend/src/main/java/com/mapidf/data/repositories/StopRepository.java`
- Modify: `backend/src/main/java/com/mapidf/services/NetworkQueryService.java`
- Create: `backend/src/main/resources/db/migration/V3__indexes_multiligne.sql`
- Test: `backend/src/test/java/com/mapidf/data/SchemaIT.java` (ajout d'une assertion d'index)

**Interfaces:**
- Produces: `StopRepository.findDistinctStopsByRouteGtfsId(String)` → `List<Stop>` distincts d'une route ; `getShape` s'en sert au lieu de charger tous les `stop_times`.
- Consumes (NetworkQueryService) : `StopRepository`.

- [ ] **Step 1 : Ajouter la requête repository**

Dans `StopRepository`, ajouter :

```java
    @Query("""
        SELECT DISTINCT s FROM StopTime st
        JOIN st.stop s
        WHERE st.trip.route.gtfsId = :routeId
        ORDER BY s.gtfsId
        """)
    List<Stop> findDistinctStopsByRouteGtfsId(@Param("routeId") String routeId);
```

Ajouter les imports si absents : `import java.util.List;`, `import com.mapidf.data.entity.Stop;`, `import org.springframework.data.jpa.repository.Query;`, `import org.springframework.data.repository.query.Param;`.

- [ ] **Step 2 : Utiliser la requête dans `getShape`**

Dans `NetworkQueryService`, injecter `StopRepository` (ajouter le champ ; le `@AllArgsConstructor` s'en charge) et remplacer le regroupement :

```java
        Map<String, List<Stop>> byStation = stopTimeRepository.findScheduleByRouteGtfsId(gtfsRouteId).stream()
            .map(StopTime::getStop)
            .distinct()
            .collect(Collectors.groupingBy(NetworkQueryService::stationKey, LinkedHashMap::new, Collectors.toList()));
```

par :

```java
        Map<String, List<Stop>> byStation = stopRepository.findDistinctStopsByRouteGtfsId(gtfsRouteId).stream()
            .collect(Collectors.groupingBy(NetworkQueryService::stationKey, LinkedHashMap::new, Collectors.toList()));
```

Ajouter le champ :

```java
    private final RouteRepository routeRepository;
    private final StopTimeRepository stopTimeRepository;
    private final StopRepository stopRepository;
```

Ajouter l'import `import com.mapidf.data.repositories.StopRepository;`. Retirer les imports devenus inutiles (`StopTime`) uniquement s'ils ne servent plus ailleurs dans le fichier.

- [ ] **Step 3 : Créer la migration V3**

Créer `backend/src/main/resources/db/migration/V3__indexes_multiligne.sql` :

```sql
-- Index préparant le multi-ligne (coût nul en mono-ligne).
-- parent_station : utilisé par StopRepository.findByParentStation (résolution station /departures).
-- stop_id : FK de stop_time non indexée en V1 ; utile dès qu'on requête par arrêt.
CREATE INDEX idx_stop_parent_station ON stop (parent_station);
CREATE INDEX idx_stop_time_stop ON stop_time (stop_id);
```

- [ ] **Step 4 : Assertion d'index dans `SchemaIT`**

Ajouter dans `SchemaIT` une vérification que les index existent (adapter au style d'assertion déjà présent dans la classe ; le test s'exécute contre le conteneur PostGIS avec Flyway appliqué) :

```java
    @Test
    void createsMultilineIndexes() {
        List<String> indexes = jdbcTemplate.queryForList(
            "SELECT indexname FROM pg_indexes WHERE schemaname = 'public'", String.class);
        assertThat(indexes).contains("idx_stop_parent_station", "idx_stop_time_stop");
    }
```

Si `SchemaIT` n'a pas déjà un `JdbcTemplate` injecté, l'ajouter (`@Autowired JdbcTemplate jdbcTemplate;`, import `org.springframework.jdbc.core.JdbcTemplate`) — sinon réutiliser le mécanisme d'accès DB déjà présent dans la classe.

- [ ] **Step 5 : Vérifier (IT inclus)**

Run: `cd backend && JAVA_HOME=/home/abodet/.jdks/temurin-25.0.3 ./mvnw verify`
Expected: BUILD SUCCESS — la migration V3 s'applique, `SchemaIT` voit les index, `LineControllerShapeIT`/`NetworkQueryServiceIT` confirment que `/shape` renvoie les mêmes stations qu'avant (optimisation sans changement de sortie).

- [ ] **Step 6 : Commit**

```bash
git add backend/src/main/java/com/mapidf/data/repositories/StopRepository.java backend/src/main/java/com/mapidf/services/NetworkQueryService.java backend/src/main/resources/db/migration/V3__indexes_multiligne.sql backend/src/test/java/com/mapidf/data/SchemaIT.java
git commit -m "perf(back): requête arrêts distincts pour /shape + index (V3)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 7 : Annulation des fetch de departures (front)

**Files:**
- Modify: `frontend/src/App.tsx` (`onStationClick` L64-84, effet de refresh L121-140)

**Interfaces:**
- Produces: aucune API changée. Comportement : la réponse d'un fetch de departures obsolète est annulée + ignorée ; le refresh périodique ne peut plus empiler deux fetch.

- [ ] **Step 1 : `onStationClick` avec `AbortController` + garde de séquence**

Le composant garde un ref du contrôleur courant. En tête de `App`, ajouter :

```tsx
  const departuresAbort = useRef<AbortController | null>(null);
```

Remplacer `onStationClick` par une version qui annule la requête précédente et n'applique que la réponse de la requête courante :

```tsx
    const onStationClick = async (e: maplibregl.MapLayerMouseEvent) => {
      const id = e.features?.[0]?.properties?.id as string | undefined;
      const coords = (e.features?.[0]?.geometry as GeoJSON.Point | undefined)?.coordinates;
      if (!id) {
        return;
      }
      // Sélection exclusive : ouvrir une station ferme le suivi d'un train.
      setSelected(null);
      setSelectedTripId(null);
      setFollow(false);
      map.setFilter("stops-selected", ["==", ["get", "id"], id]);
      if (coords) {
        map.easeTo({ center: coords as [number, number] });
      }
      setSelectedStationId(id);
      departuresAbort.current?.abort();
      const controller = new AbortController();
      departuresAbort.current = controller;
      try {
        const fresh = await fetchDepartures(LINE_ID, id, controller.signal);
        if (!controller.signal.aborted) {
          setStation(fresh);
        }
      } catch {
        if (!controller.signal.aborted) {
          setStation(null);
        }
      }
    };
```

- [ ] **Step 2 : Refresh périodique en `setTimeout` récursif**

Remplacer l'effet `setInterval` (L121-140) par une boucle `setTimeout` récursive qui attend la fin de chaque fetch avant de replanifier :

```tsx
  useEffect(() => {
    if (!selectedStationId) {
      return;
    }
    let cancelled = false;
    let timer: number;
    const controller = new AbortController();
    const tick = async () => {
      try {
        const fresh = await fetchDepartures(LINE_ID, selectedStationId, controller.signal);
        if (!cancelled) {
          setStation(fresh);
        }
      } catch {
        // on conserve l'affichage courant
      }
      if (!cancelled) {
        timer = window.setTimeout(tick, VEHICLE_POLL_MS);
      }
    };
    timer = window.setTimeout(tick, VEHICLE_POLL_MS);
    return () => {
      cancelled = true;
      window.clearTimeout(timer);
      controller.abort();
    };
  }, [selectedStationId]);
```

- [ ] **Step 3 : Ajouter le paramètre `signal` à `fetchDepartures`**

Dans `frontend/src/api/lines.ts`, ajouter un paramètre optionnel `signal` à `fetchDepartures` et le passer au `fetch` sous-jacent (ne pas changer les autres fonctions). Exemple (adapter à la signature réelle du fichier) :

```ts
export async function fetchDepartures(lineId: string, stationId: string, signal?: AbortSignal) {
  const res = await fetch(`/api/lines/${lineId}/stations/${encodeURIComponent(stationId)}/departures`, { signal });
  if (!res.ok) throw new Error(`departures ${res.status}`);
  return (await res.json()) as DeparturesResponse;
}
```

- [ ] **Step 4 : Build**

Run: `cd frontend && npm run build`
Expected: build OK, aucune erreur TypeScript.

- [ ] **Step 5 : Contrôle visuel (utilisateur)**

Cliquer rapidement deux stations différentes : le panneau affiche toujours les passages de la **dernière** station cliquée (pas d'inversion). Le panneau se rafraîchit toujours toutes les ~4 s sans à-coups.

- [ ] **Step 6 : Commit**

```bash
git add frontend/src/App.tsx frontend/src/api/lines.ts
git commit -m "fix(front): annuler les fetch de departures obsolètes (plus de panneau incohérent)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 8 : Nettoyage complet des couches et handlers (front)

**Files:**
- Modify: `frontend/src/map/VehicleLayer.ts` (`destroy` L220-230 ; ajout d'un champ `moveHandler` nullable)
- Modify: `frontend/src/map/useLineShape.ts` (handlers `mouseenter`/`mouseleave` L92-95 + cleanup L99-104)

**Interfaces:**
- Produces (VehicleLayer) : champ `private moveHandler: (() => void) | null = null;` (assigné en Phase 2, retiré ici dès maintenant) ; `destroy()` retire couches + source + image + `moveHandler`.

- [ ] **Step 1 : `destroy()` complet + champ `moveHandler`**

Dans `VehicleLayer`, ajouter le champ (près des autres champs privés, après `highlightedTripIds`) :

```ts
  private moveHandler: (() => void) | null = null;
```

Remplacer `destroy()` :

```ts
  destroy() {
    if (this.raf) {
      cancelAnimationFrame(this.raf);
    }
    this.raf = 0;
    if (this.cancelReady) {
      this.cancelReady();
      this.cancelReady = null;
    }
    if (this.moveHandler) {
      this.map.off("move", this.moveHandler);
      this.moveHandler = null;
    }
    for (const id of ["vehicles", "vehicles-halo", "vehicles-highlight"]) {
      if (this.map.getLayer(id)) {
        this.map.removeLayer(id);
      }
    }
    if (this.map.getSource("vehicles")) {
      this.map.removeSource("vehicles");
    }
    if (this.map.hasImage("vehicle-arrow")) {
      this.map.removeImage("vehicle-arrow");
    }
    this.anims.clear();
  }
```

- [ ] **Step 2 : Retirer les handlers de curseur dans `useLineShape`**

Dans `useLineShape`, extraire les handlers en références nommées (pour pouvoir les `off`) et les retirer au cleanup. Remplacer les 4 `map.on(...)` (L92-95) :

```ts
        const cursorEnter = () => { map.getCanvas().style.cursor = "pointer"; };
        const cursorLeave = () => { map.getCanvas().style.cursor = ""; };
        map.on("mouseenter", "stops", cursorEnter);
        map.on("mouseleave", "stops", cursorLeave);
        map.on("mouseenter", "stops-labels", cursorEnter);
        map.on("mouseleave", "stops-labels", cursorLeave);
        cleanupCursors = () => {
          map.off("mouseenter", "stops", cursorEnter);
          map.off("mouseleave", "stops", cursorLeave);
          map.off("mouseenter", "stops-labels", cursorEnter);
          map.off("mouseleave", "stops-labels", cursorLeave);
        };
```

Déclarer `let cleanupCursors: (() => void) | null = null;` à côté de `let cancelReady` (début de l'effet), et l'appeler dans le cleanup retourné :

```ts
    return () => {
      cancelled = true;
      if (cancelReady) {
        cancelReady();
      }
      if (cleanupCursors) {
        cleanupCursors();
      }
    };
```

- [ ] **Step 3 : Build**

Run: `cd frontend && npm run build`
Expected: build OK.

- [ ] **Step 4 : Contrôle visuel (utilisateur)**

Comportement inchangé (curseur main sur les stations, véhicules affichés). Pas de régression visible ; le bénéfice (pas de fuite) se vérifiera au multi-ligne.

- [ ] **Step 5 : Commit**

```bash
git add frontend/src/map/VehicleLayer.ts frontend/src/map/useLineShape.ts
git commit -m "fix(front): destroy() et cleanup complets (couches, source, image, handlers)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 9 : Garde NaN sur les véhicules (front)

**Files:**
- Modify: `frontend/src/map/VehicleLayer.ts` (`update` L146-168)

**Interfaces:**
- Produces: `update()` ignore tout véhicule dont `lat`, `lng` ou `bearing` n'est pas fini.

- [ ] **Step 1 : Filtrer les véhicules non finis**

Au début de la boucle `for (const vehicle of vehicles)` dans `update`, ajouter la garde :

```ts
    for (const vehicle of vehicles) {
      if (!Number.isFinite(vehicle.lng) || !Number.isFinite(vehicle.lat) || !Number.isFinite(vehicle.bearing)) {
        continue; // position/orientation invalide → on n'anime pas une géométrie NaN
      }
      seen.add(vehicle.tripId);
      // ... reste inchangé ...
```

- [ ] **Step 2 : Build**

Run: `cd frontend && npm run build`
Expected: build OK.

- [ ] **Step 3 : Contrôle visuel (utilisateur)**

Comportement inchangé sur données normales (le backend ne renvoie pas de NaN aujourd'hui) ; la garde protège d'un futur cas dégénéré.

- [ ] **Step 4 : Commit**

```bash
git add frontend/src/map/VehicleLayer.ts
git commit -m "fix(front): ignorer les véhicules à position/bearing non finis

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 10 : Mineurs front (label, clé de liste, fetch /shape unique)

**Files:**
- Modify: `frontend/src/ui/VehiclePanel.tsx` (L54)
- Modify: `frontend/src/ui/StopPanel.tsx` (L53)
- Modify: `frontend/src/map/useLineShape.ts` (exposition de la couleur)
- Modify: `frontend/src/App.tsx` (suppression du fetchShape dédié couleur)

**Interfaces:**
- Produces (useLineShape) : signature `useLineShape(map, lineId, onColor?: (color: string) => void)` — `onColor` est appelé une fois la réponse `/shape` reçue.

- [ ] **Step 1 : Label sans « GPS »**

Dans `VehiclePanel.tsx` (L54), remplacer le libellé affiché quand `source === "REALTIME"` par un libellé sans GPS. Exemple (adapter au JSX exact) :

```tsx
        {vehicle.source === "REALTIME" ? "temps réel" : "position estimée"}
```

(But : ne jamais afficher « GPS » — il n'y a pas de GPS en métro.)

- [ ] **Step 2 : Clé de liste stable dans `StopPanel`**

Dans `StopPanel.tsx` (L53), remplacer `key={i}` par `key={p.journeyRef}` sur l'élément de passage (et retirer l'index `i` du `.map` s'il n'est plus utilisé).

- [ ] **Step 3 : `useLineShape` expose la couleur**

Modifier la signature et appeler `onColor` avec `shape.color` une fois la réponse reçue :

```ts
export function useLineShape(map: MlMap | null, lineId: string, onColor?: (color: string) => void) {
  useEffect(() => {
    if (!map) {
      return;
    }
    let cancelled = false;
    let cancelReady: (() => void) | null = null;
    let cleanupCursors: (() => void) | null = null;
    fetchShape(lineId).then((shape) => {
      if (cancelled) {
        return;
      }
      onColor?.(shape.color);
      const draw = () => {
        // ... inchangé ...
```

- [ ] **Step 4 : `App` consomme la couleur via `useLineShape` et supprime son fetch dédié**

Dans `App.tsx`, remplacer :

```tsx
  useLineShape(map, LINE_ID);
  useEffect(() => {
    fetchShape(LINE_ID).then((s) => setLineColor(s.color)).catch(() => {});
  }, []);
```

par :

```tsx
  useLineShape(map, LINE_ID, setLineColor);
```

Retirer l'import `fetchShape` de `App.tsx` s'il n'est plus utilisé ailleurs.

- [ ] **Step 5 : Build**

Run: `cd frontend && npm run build`
Expected: build OK, aucune erreur TypeScript (vérifier notamment qu'aucun import inutilisé ne subsiste).

- [ ] **Step 6 : Contrôle visuel (utilisateur)**

La couleur de la ligne s'applique toujours (tracé, ronds, flèches) ; le panneau véhicule n'affiche plus « GPS ». Réseau : une seule requête `/shape` au chargement.

- [ ] **Step 7 : Commit**

```bash
git add frontend/src/ui/VehiclePanel.tsx frontend/src/ui/StopPanel.tsx frontend/src/map/useLineShape.ts frontend/src/App.tsx
git commit -m "fix(front): label sans GPS, clé de liste stable, /shape fetché une seule fois

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## PHASE 2 — Lot 2 (refactor perf du rendu)

### Task 11 : Sélection/surlignage en feature-state

**Files:**
- Modify: `frontend/src/map/VehicleLayer.ts` (source, couches halo/highlight, `setSelected`, `setHighlighted`, `step`, `ensureLayer`)

**Interfaces:**
- Produces: source `vehicles` avec `promoteId: "tripId"` ; couches `vehicles-halo`/`vehicles-highlight` permanentes pilotées par `feature-state` (opacité) ; méthode `private applySelectionState()` (ré-applique `selected`/`highlighted` via `setFeatureState`) réutilisée en Task 12.

- [ ] **Step 1 : `promoteId` sur la source**

Dans `ensureLayer`, l'ajout de source devient :

```ts
      this.map.addSource("vehicles", {
        type: "geojson",
        promoteId: "tripId",
        data: this.featureCollection([]),
      });
```

- [ ] **Step 2 : Couches halo/highlight pilotées par feature-state**

Remplacer la couche `vehicles-halo` (filtre → opacité par état) :

```ts
      this.map.addLayer({
        id: "vehicles-halo",
        type: "circle",
        source: "vehicles",
        paint: {
          "circle-radius": 12,
          "circle-color": "rgba(29,78,216,0.15)",
          "circle-stroke-color": "#1d4ed8",
          "circle-stroke-width": 3,
          "circle-opacity": ["case", ["boolean", ["feature-state", "selected"], false], 1, 0],
          "circle-stroke-opacity": ["case", ["boolean", ["feature-state", "selected"], false], 1, 0],
        },
      });
```

Et `vehicles-highlight` :

```ts
      this.map.addLayer({
        id: "vehicles-highlight",
        type: "circle",
        source: "vehicles",
        paint: {
          "circle-radius": 11,
          "circle-color": "rgba(0,0,0,0)",
          "circle-stroke-color": "#111",
          "circle-stroke-width": 2.5,
          "circle-stroke-opacity": ["case", ["boolean", ["feature-state", "highlighted"], false], 1, 0],
        },
      });
```

À la fin de la fonction `add()` (après l'ajout de la couche `vehicles`), ré-appliquer l'état courant (utile si `setSelected`/`setHighlighted` ont été appelés avant que la source existe) :

```ts
      this.applySelectionState();
```

- [ ] **Step 3 : `applySelectionState` + setters via feature-state**

Ajouter la méthode privée et réécrire les setters :

```ts
  private applySelectionState() {
    if (!this.map.getSource("vehicles")) {
      return;
    }
    this.map.removeFeatureState({ source: "vehicles" });
    if (this.selectedTripId) {
      this.map.setFeatureState({ source: "vehicles", id: this.selectedTripId }, { selected: true });
    }
    for (const id of this.highlightedTripIds) {
      this.map.setFeatureState({ source: "vehicles", id }, { highlighted: true });
    }
  }

  setSelected(tripId: string | null) {
    this.selectedTripId = tripId;
    this.applySelectionState();
  }

  setHighlighted(ids: Set<string>) {
    this.highlightedTripIds = ids;
    this.applySelectionState();
  }
```

- [ ] **Step 4 : `step` n'écrit plus `selected`/`highlighted` en propriétés, et ré-applique l'état**

Dans `step` (boucle de rendu), retirer les lignes `const selected = ...` / `const highlighted = ...` et les clés `selected`/`highlighted` de `properties`. Conserver le calcul de `followPoint` en comparant directement `anim.vehicle.tripId === this.selectedTripId`. Après `source.setData(...)`, ajouter `this.applySelectionState();`. Le bloc devient :

```ts
        const features = [...this.anims.values()].map((anim) => {
          const [lng, lat] = this.pointAt(anim, now);
          if (anim.vehicle.tripId === this.selectedTripId && this.follow) {
            followPoint = [lng, lat];
          }
          return {
            type: "Feature",
            properties: {
              tripId: anim.vehicle.tripId,
              source: anim.vehicle.source,
              bearing: anim.bearing,
              headsign: anim.vehicle.headsign,
              nextStop: anim.vehicle.nextStop,
              expectedTime: anim.vehicle.expectedTime,
              status: anim.vehicle.status,
            },
            geometry: { type: "Point", coordinates: [lng, lat] },
          } as GeoJSON.Feature;
        });
        source.setData(this.featureCollection(features));
        this.applySelectionState();
        if (followPoint) {
          this.map.jumpTo({ center: followPoint });
        }
```

- [ ] **Step 5 : Build**

Run: `cd frontend && npm run build`
Expected: build OK.

- [ ] **Step 6 : Contrôle visuel (utilisateur)**

Sélectionner un train : halo bleu instantané. Ouvrir une station : anneaux noirs sur les véhicules concernés. Changer de sélection plusieurs fois : réactif, pas d'artefact. Le suivi caméra fonctionne toujours.

- [ ] **Step 7 : Commit**

```bash
git add frontend/src/map/VehicleLayer.ts
git commit -m "perf(front): sélection/surlignage en feature-state (plus de rebuild à la sélection)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 12 : Boucle de rendu — throttle + idle + culling viewport

**Files:**
- Modify: `frontend/src/map/VehicleLayer.ts` (constante de module, `startLoop`/`step` → `startLoop` + `render`, `setFollow`, `update`, `ensureLayer`)

**Interfaces:**
- Consumes: `applySelectionState()` (Task 11), champ `moveHandler` (Task 8).
- Produces: rendu throttlé ~15 fps, boucle rAF arrêtée à l'idle et réveillée par `update()`/`setFollow(true)`/`map.move`, `FeatureCollection` limitée au viewport élargi.

- [ ] **Step 1 : Constante de throttle**

En tête de module (près de `SNAP_DISTANCE_M`), ajouter :

```ts
// Le métro est lent : reconstruire la source ~15 fps au lieu de 60 suffit visuellement
// et divise d'autant le coût de setData (goulot à l'échelle réseau).
const RENDER_INTERVAL_MS = 66;
```

Ajouter aussi les champs privés (près de `raf`) :

```ts
  private lastRenderAt = 0;
```

- [ ] **Step 2 : Extraire `render(now)` et réécrire `startLoop`**

Remplacer entièrement `startLoop()` (l'ancien `step` inline disparaît au profit de `render` + une boucle qui throttle et s'arrête à l'idle) :

```ts
  private isAnimating(now: number): boolean {
    for (const anim of this.anims.values()) {
      if (now - anim.start < this.durationMs) {
        return true;
      }
    }
    return false;
  }

  private render(now: number) {
    const source = this.map.getSource("vehicles") as GeoJSONSource | undefined;
    if (!source) {
      return;
    }
    // Culling : on n'envoie à la source que les véhicules dans le viewport élargi (marge 20 %).
    // Les anims restent maintenues pour tous → le tween survit à une sortie/entrée d'écran.
    const bounds = this.map.getBounds();
    const west = bounds.getWest();
    const east = bounds.getEast();
    const south = bounds.getSouth();
    const north = bounds.getNorth();
    const padX = (east - west) * 0.2;
    const padY = (north - south) * 0.2;

    let followPoint: [number, number] | null = null;
    const features: GeoJSON.Feature[] = [];
    for (const anim of this.anims.values()) {
      const [lng, lat] = this.pointAt(anim, now);
      if (anim.vehicle.tripId === this.selectedTripId && this.follow) {
        followPoint = [lng, lat];
      }
      if (lng < west - padX || lng > east + padX || lat < south - padY || lat > north + padY) {
        continue;
      }
      features.push({
        type: "Feature",
        properties: {
          tripId: anim.vehicle.tripId,
          source: anim.vehicle.source,
          bearing: anim.bearing,
          headsign: anim.vehicle.headsign,
          nextStop: anim.vehicle.nextStop,
          expectedTime: anim.vehicle.expectedTime,
          status: anim.vehicle.status,
        },
        geometry: { type: "Point", coordinates: [lng, lat] },
      } as GeoJSON.Feature);
    }
    source.setData(this.featureCollection(features));
    this.applySelectionState();
    if (followPoint) {
      this.map.jumpTo({ center: followPoint });
    }
  }

  private startLoop() {
    if (this.raf) {
      return;
    }
    const step = (now: number) => {
      if (now - this.lastRenderAt >= RENDER_INTERVAL_MS) {
        this.render(now);
        this.lastRenderAt = now;
      }
      if (this.isAnimating(now)) {
        this.raf = requestAnimationFrame(step);
      } else {
        // Plus rien à animer : rendu final puis arrêt de la boucle (CPU au repos).
        this.raf = 0;
        this.render(now);
      }
    };
    this.raf = requestAnimationFrame(step);
  }
```

- [ ] **Step 3 : Réveil sur pan/zoom (`map.move`)**

Dans `ensureLayer`, à la fin de `add()` (après `applySelectionState()`), attacher le handler de mouvement stocké dans `moveHandler` (champ ajouté en Task 8) :

```ts
      this.moveHandler = () => {
        if (this.raf) {
          return; // la boucle rend déjà
        }
        const now = performance.now();
        if (now - this.lastRenderAt < RENDER_INTERVAL_MS) {
          return; // throttle
        }
        this.lastRenderAt = now;
        this.render(now);
      };
      this.map.on("move", this.moveHandler);
```

- [ ] **Step 4 : `setFollow` relance la boucle**

Pour que réactiver le suivi recentre immédiatement même à l'arrêt :

```ts
  setFollow(follow: boolean) {
    this.follow = follow;
    if (follow) {
      this.startLoop();
    }
  }
```

(`update()` appelle déjà `startLoop()` à la fin — inchangé — donc chaque poll réveille la boucle.)

- [ ] **Step 5 : Build**

Run: `cd frontend && npm run build`
Expected: build OK.

- [ ] **Step 6 : Contrôle visuel (utilisateur)**

- Les métros avancent de façon fluide (le throttle ne se voit pas).
- Carte laissée au repos (aucun poll en cours d'animation) : plus d'activité CPU continue (la boucle s'arrête ; vérifiable au profileur ou à la conso).
- Pan/zoom : les véhicules restent bien positionnés et apparaissent/disparaissent proprement aux bords (culling).
- Suivi d'un train : la caméra suit toujours ; réactiver le suivi recentre immédiatement.

- [ ] **Step 7 : Commit**

```bash
git add frontend/src/map/VehicleLayer.ts
git commit -m "perf(front): boucle de rendu throttlée + arrêt à l'idle + culling viewport

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Self-review (déjà effectuée par l'auteur du plan)

- **Couverture spec** : Lot 1 unités 1→13 mappées sur Tasks 1→10 (isolation parse=T1, stopKey=T2, journeyRef=T3, timeouts+fixedDelay=T4, cache LineSchedule=T5, requête distincte+index=T6, annulation fetch=T7, destroy/handlers=T8, garde NaN=T9, mineurs label/clé/shape=T10). Lot 2 : feature-state=T11, throttle+idle+culling=T12. Rien d'orphelin.
- **Ordre** : le champ `moveHandler` est introduit en T8 (Lot 1, `destroy`) et assigné en T12 (Lot 2) — cohérent, `destroy` le retire dès T8 même s'il est encore `null`.
- **Types** : `applySelectionState()` défini en T11, consommé en T12 ; `render(now: number)` remplace `step` ; `fetchDepartures(lineId, stationId, signal?)` cohérent entre T7 (appelants) et sa définition.
- **Hors périmètre** confirmé absent du plan : multi-ligne (loader/config/wiring), chantier A, `pickDirection`.
