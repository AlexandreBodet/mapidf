# Passe UX (stations regroupées, prochains passages, véhicules directionnels) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Solidifier l'UX mono-ligne avant le multi-ligne : stations regroupées par `parent_station`, prochains passages à un arrêt, véhicules directionnels colorés par ligne, et gains rapides (bouton nord, curseur, ✕, légende+compteur).

**Architecture:** Backend Spring Boot — nouvelle colonne `parent_station` (migration Flyway), regroupement des quais côté requête `/shape`, endpoint departures agrégé depuis le snapshot temps réel en mémoire (pas d'appel PRIM). Frontend React/MapLibre — stations regroupées cliquables avec labels, panneau prochains passages exclusif du panneau train, véhicules en flèches SDF orientées sur le `bearing`.

**Tech Stack:** Java 25 / Spring Boot 4.1 / Lombok / Jackson 3 / JTS / Flyway / PostGIS / Testcontainers (backend) ; React 18 / TypeScript / MapLibre GL / Vite (frontend).

## Global Constraints

- **Jackson 3** : sur un `JsonNode`, `.asString()` (jamais `.asText()`). Imports `tools.jackson.*`.
- **Secrets** : `PRIM_API_KEY` dans `.env`, jamais commité.
- **TDD backend** : test qui échoue → implémentation minimale → test vert → commit. Vérif de référence : `cd backend && ./mvnw verify`.
- **Frontend** : pas de tests unitaires (convention projet). Vérif = `cd frontend && npm run build` + contrôle visuel.
- **Perf (ne pas aggraver)** : ne PAS reconstruire par frame les couches statiques (flèche, halo, labels). Seule la source `vehicles` bouge par frame, comme aujourd'hui. Le refactor de la boucle rAF est HORS PÉRIMÈTRE (prérequis multi-ligne, cf. spec).
- **Contrat inchangé** hormis : `/shape` `StopDto` gagne `platformIds`, et nouvel endpoint `/lines/{id}/stations/{stationId}/departures`.
- Commits en français, préfixe conventionnel (`feat:`, `test:`, `docs:`), trailer `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`.

---

## File Structure

**Backend — créés :**
- `backend/src/main/resources/db/migration/V2__stop_parent_station.sql` — migration colonne.
- `backend/src/main/java/com/mapidf/controllers/lines/DeparturesResponse.java` — DTO réponse departures.
- `backend/src/main/java/com/mapidf/services/StationDepartureService.java` — agrégation pure (testable sans DB).
- `backend/src/test/java/com/mapidf/services/StationDepartureServiceTest.java` — tests unitaires agrégation.
- `backend/src/test/java/com/mapidf/services/NetworkQueryServiceIT.java` — test regroupement stations.
- `backend/src/test/java/com/mapidf/controllers/lines/LineControllerDeparturesIT.java` — test endpoint (404 + vide).
- `backend/src/test/resources/gtfs-parent.zip` — fixture GTFS avec `parent_station`.

**Backend — modifiés :**
- `backend/src/main/java/com/mapidf/data/entity/Stop.java` — champ `parentStation`.
- `backend/src/main/java/com/mapidf/gtfs/GtfsStaticLoader.java:186-191` — lit `parent_station`.
- `backend/src/main/java/com/mapidf/controllers/lines/ShapeResponse.java` — `StopDto.platformIds`.
- `backend/src/main/java/com/mapidf/services/NetworkQueryService.java` — regroupement.
- `backend/src/main/java/com/mapidf/data/repositories/StopRepository.java` — `findByParentStation`, `findByGtfsId`.
- `backend/src/main/java/com/mapidf/data/enums/ErrorCode.java` — `STATION_NOT_FOUND`.
- `backend/src/main/java/com/mapidf/controllers/lines/LineController.java` — endpoint departures.
- `backend/src/test/java/com/mapidf/gtfs/GtfsStaticLoaderIT.java` — test `parent_station`.

**Frontend — créés :**
- `frontend/src/ui/StopPanel.tsx` — panneau prochains passages.
- `frontend/src/ui/Legend.tsx` — légende + compteur.

**Frontend — modifiés :**
- `frontend/src/api/types.ts` — `platformIds`, `DeparturesResponse`.
- `frontend/src/api/lines.ts` — `fetchDepartures`.
- `frontend/src/map/useLineShape.ts` — labels stations, curseur.
- `frontend/src/map/VehicleLayer.ts` — flèches SDF, couleur ligne, halo.
- `frontend/src/map/useVehicles.ts` — passe la couleur, remonte le compteur.
- `frontend/src/map/MapView.tsx` — `NavigationControl`.
- `frontend/src/App.tsx` — sélection exclusive station/train, clic station, légende.
- `frontend/src/ui/VehiclePanel.tsx` — croix ✕ agrandie.

---

## Task 1: Colonne `parent_station` (entité + migration + loader)

**Files:**
- Create: `backend/src/main/resources/db/migration/V2__stop_parent_station.sql`
- Create: `backend/src/test/resources/gtfs-parent.zip`
- Modify: `backend/src/main/java/com/mapidf/data/entity/Stop.java`
- Modify: `backend/src/main/java/com/mapidf/gtfs/GtfsStaticLoader.java`
- Test: `backend/src/test/java/com/mapidf/gtfs/GtfsStaticLoaderIT.java`

**Interfaces:**
- Produces: `Stop.getParentStation() : String` (nullable). Fixture `gtfs-parent.zip` route `RP`, 5 quais : `PA0`/`PA1` (parent `SAA`), `PB0`/`PB1` (parent `SAB`), `PC` (sans parent).

- [ ] **Step 1: Créer la fixture GTFS avec `parent_station`**

Run (génère le zip de test) :

```bash
python3 - <<'PY'
import zipfile, io
files = {
 "routes.txt": "route_id,route_short_name,route_color\nRP,9,D5C900\n",
 "trips.txt": "route_id,trip_id,trip_headsign,direction_id,shape_id\n"
              "RP,TOUT,Vers B,0,SH1\nRP,TIN,Vers A,1,SH1\n",
 "shapes.txt": "shape_id,shape_pt_sequence,shape_pt_lon,shape_pt_lat\n"
               "SH1,1,2.300,48.850\nSH1,2,2.310,48.850\nSH1,3,2.320,48.850\n",
 "stop_times.txt": "trip_id,stop_id,stop_sequence,arrival_time,departure_time\n"
                   "TOUT,PA0,1,08:00:00,08:00:00\nTOUT,PB0,2,08:05:00,08:05:00\nTOUT,PC,3,08:10:00,08:10:00\n"
                   "TIN,PC,1,09:00:00,09:00:00\nTIN,PB1,2,09:05:00,09:05:00\nTIN,PA1,3,09:10:00,09:10:00\n",
 "stops.txt": "stop_id,stop_name,stop_lon,stop_lat,parent_station\n"
              "PA0,Alpha,2.300,48.850,SAA\nPA1,Alpha,2.3001,48.8501,SAA\n"
              "PB0,Beta,2.310,48.850,SAB\nPB1,Beta,2.3101,48.8501,SAB\n"
              "PC,Gamma,2.320,48.850,\n",
}
with zipfile.ZipFile("backend/src/test/resources/gtfs-parent.zip","w",zipfile.ZIP_DEFLATED) as z:
    for n,c in files.items():
        z.writestr(n,c)
print("gtfs-parent.zip créé")
PY
```

Expected : `gtfs-parent.zip créé`

- [ ] **Step 2: Écrire le test qui échoue (loader lit `parent_station`)**

Ajouter dans `GtfsStaticLoaderIT.java` :

```java
    @Test
    void readsParentStationFromStopsAndLeavesItNullWhenAbsent() throws Exception {
        try (var in = getClass().getResourceAsStream("/gtfs-parent.zip")) {
            loader.loadFromZip(in, "RP");
        }
        var stops = stopRepository.findAll();
        assertThat(stops).hasSize(5);
        assertThat(stops).filteredOn(s -> s.getGtfsId().equals("PA0"))
            .singleElement().extracting("parentStation").isEqualTo("SAA");
        assertThat(stops).filteredOn(s -> s.getGtfsId().equals("PA1"))
            .singleElement().extracting("parentStation").isEqualTo("SAA");
        assertThat(stops).filteredOn(s -> s.getGtfsId().equals("PC"))
            .singleElement().extracting("parentStation").isNull();
    }
```

- [ ] **Step 3: Lancer le test → échoue à la compilation**

Run: `cd backend && ./mvnw test -Dtest=GtfsStaticLoaderIT#readsParentStationFromStopsAndLeavesItNullWhenAbsent`
Expected: FAIL — `getParentStation()`/`parentStation` inconnu (ne compile pas).

- [ ] **Step 4: Migration Flyway**

Créer `V2__stop_parent_station.sql` :

```sql
ALTER TABLE stop ADD COLUMN parent_station TEXT;
```

- [ ] **Step 5: Champ sur l'entité `Stop`**

Dans `Stop.java`, après le champ `name` (ligne ~39) :

```java
    @Column(name = "parent_station")
    private String parentStation;
```

- [ ] **Step 6: Le loader lit la colonne**

Dans `GtfsStaticLoader.persistStops` (bloc `Stop.builder()`, ~186-191), ajouter `.parentStation(...)` :

```java
                stopsToSave.add(Stop.builder()
                    .gtfsId(stopId)
                    .name(r.get("stop_name"))
                    .parentStation(safe(r, "parent_station"))
                    .geom(geometryFactory.createPoint(new Coordinate(
                        Double.parseDouble(r.get("stop_lon")), Double.parseDouble(r.get("stop_lat")))))
                    .build());
```

- [ ] **Step 7: Lancer le test → vert**

Run: `cd backend && ./mvnw test -Dtest=GtfsStaticLoaderIT`
Expected: PASS (3 tests existants + le nouveau).

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/resources/db/migration/V2__stop_parent_station.sql \
        backend/src/test/resources/gtfs-parent.zip \
        backend/src/main/java/com/mapidf/data/entity/Stop.java \
        backend/src/main/java/com/mapidf/gtfs/GtfsStaticLoader.java \
        backend/src/test/java/com/mapidf/gtfs/GtfsStaticLoaderIT.java
git commit -m "feat(gtfs): capter parent_station au chargement des arrêts

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 2: Regroupement des quais par station dans `/shape`

**Files:**
- Modify: `backend/src/main/java/com/mapidf/controllers/lines/ShapeResponse.java`
- Modify: `backend/src/main/java/com/mapidf/services/NetworkQueryService.java`
- Test: `backend/src/test/java/com/mapidf/services/NetworkQueryServiceIT.java` (create)

**Interfaces:**
- Consumes: `Stop.getParentStation()` (Task 1).
- Produces: `ShapeResponse.StopDto` avec `List<String> platformIds`. Clé de station = `parentStation` si non nul/vide, sinon `gtfsId` du quai. `lat`/`lng` = moyenne des quais membres.

- [ ] **Step 1: Écrire le test de regroupement qui échoue**

Créer `NetworkQueryServiceIT.java` :

```java
package com.mapidf.services;

import com.mapidf.MapIdfTest;
import com.mapidf.controllers.lines.ShapeResponse;
import com.mapidf.gtfs.GtfsStaticLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

@MapIdfTest
class NetworkQueryServiceIT {

    @Autowired GtfsStaticLoader loader;
    @Autowired NetworkQueryService service;

    @BeforeEach
    void load() throws Exception {
        try (var in = getClass().getResourceAsStream("/gtfs-parent.zip")) {
            loader.loadFromZip(in, "RP");
        }
    }

    @Test
    void groupsPlatformsSharingAParentIntoOneStation() {
        ShapeResponse shape = service.getShape("RP");

        // 5 quais → 3 stations (SAA, SAB, PC seul)
        assertThat(shape.getStops()).hasSize(3);

        ShapeResponse.StopDto alpha = shape.getStops().stream()
            .filter(s -> s.getName().equals("Alpha")).findFirst().orElseThrow();
        assertThat(alpha.getId()).isEqualTo("SAA");
        assertThat(alpha.getPlatformIds()).containsExactlyInAnyOrder("PA0", "PA1");
        assertThat(alpha.getLng()).isCloseTo(2.30005, within(1e-4)); // moyenne des deux quais

        ShapeResponse.StopDto gamma = shape.getStops().stream()
            .filter(s -> s.getName().equals("Gamma")).findFirst().orElseThrow();
        assertThat(gamma.getId()).isEqualTo("PC");           // sans parent → clé = gtfsId
        assertThat(gamma.getPlatformIds()).containsExactly("PC");
    }
}
```

- [ ] **Step 2: Lancer → échoue**

Run: `cd backend && ./mvnw test -Dtest=NetworkQueryServiceIT`
Expected: FAIL — `getPlatformIds()` inconnu (ne compile pas).

- [ ] **Step 3: Ajouter `platformIds` au DTO**

Dans `ShapeResponse.java`, `StopDto` :

```java
    @Value
    @Builder
    public static class StopDto {
        String id;
        String name;
        double lat;
        double lng;
        List<String> platformIds;
    }
```

- [ ] **Step 4: Regrouper dans `NetworkQueryService.getShape`**

Remplacer le bloc `List<StopDto> stops = ...` (lignes ~37-46) par :

```java
        // Un quai par sens ⇒ deux arrêts GTFS par station physique. On les regroupe par
        // parent_station (clé canonique GTFS) ; à défaut on garde le quai seul (gtfs_id),
        // ce qui gère les arrêts à sens unique. lat/lng = centroïde des quais membres.
        Map<String, List<Stop>> byStation = stopTimeRepository.findScheduleByRouteGtfsId(gtfsRouteId).stream()
            .map(StopTime::getStop)
            .distinct()
            .collect(Collectors.groupingBy(NetworkQueryService::stationKey, LinkedHashMap::new, Collectors.toList()));

        List<StopDto> stops = byStation.entrySet().stream()
            .map(e -> {
                List<Stop> platforms = e.getValue();
                double lat = platforms.stream().mapToDouble(s -> s.getGeom().getY()).average().orElse(0);
                double lng = platforms.stream().mapToDouble(s -> s.getGeom().getX()).average().orElse(0);
                return StopDto.builder()
                    .id(e.getKey())
                    .name(platforms.getFirst().getName())
                    .lat(lat)
                    .lng(lng)
                    .platformIds(platforms.stream().map(Stop::getGtfsId).toList())
                    .build();
            })
            .toList();
```

Ajouter la méthode privée (sous `getShape`) et les imports :

```java
    private static String stationKey(Stop stop) {
        String parent = stop.getParentStation();
        return (parent == null || parent.isBlank()) ? stop.getGtfsId() : parent;
    }
```

Imports à ajouter en tête de fichier :

```java
import com.mapidf.data.entity.Stop;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
```

- [ ] **Step 5: Lancer → vert**

Run: `cd backend && ./mvnw test -Dtest=NetworkQueryServiceIT`
Expected: PASS.

- [ ] **Step 6: Vérifier la non-régression du shape existant**

Run: `cd backend && ./mvnw test -Dtest=LineControllerShapeIT`
Expected: PASS (gtfs-mini a 3 quais sans parent → toujours 3 stations, `platformIds` = un id chacun).

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/mapidf/controllers/lines/ShapeResponse.java \
        backend/src/main/java/com/mapidf/services/NetworkQueryService.java \
        backend/src/test/java/com/mapidf/services/NetworkQueryServiceIT.java
git commit -m "feat(shape): regrouper les quais par parent_station en une station

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 3: Endpoint « prochains passages »

**Files:**
- Create: `backend/src/main/java/com/mapidf/controllers/lines/DeparturesResponse.java`
- Create: `backend/src/main/java/com/mapidf/services/StationDepartureService.java`
- Create: `backend/src/test/java/com/mapidf/services/StationDepartureServiceTest.java`
- Create: `backend/src/test/java/com/mapidf/controllers/lines/LineControllerDeparturesIT.java`
- Modify: `backend/src/main/java/com/mapidf/data/repositories/StopRepository.java`
- Modify: `backend/src/main/java/com/mapidf/data/enums/ErrorCode.java`
- Modify: `backend/src/main/java/com/mapidf/controllers/lines/LineController.java`

**Interfaces:**
- Consumes: `RtSnapshot.LiveJourney` (`destination()`, `calls()`, `Call.stopRef/time/departureStatus`), `PositionEngine.stopKey(String)`, `poller.current().forLine(siriLineRef)`.
- Produces:
  - `DeparturesResponse(String stationName, List<Direction> directions)` avec `Direction(String destination, List<Passage> passages)` et `Passage(Instant expectedTime, String status)`.
  - `StationDepartureService.departures(String stationName, Set<String> stopKeys, List<LiveJourney> journeys, Instant now, int perDirection) : DeparturesResponse`.
  - `StopRepository.findByParentStation(String) : List<Stop>`, `findByGtfsId(String) : Optional<Stop>`.
  - `ErrorCode.STATION_NOT_FOUND`.
  - Endpoint `GET /lines/{id}/stations/{stationId}/departures`.

- [ ] **Step 1: DTO de réponse**

Créer `DeparturesResponse.java` :

```java
package com.mapidf.controllers.lines;

import java.time.Instant;
import java.util.List;

public record DeparturesResponse(String stationName, List<Direction> directions) {

    public record Direction(String destination, List<Passage> passages) {
    }

    public record Passage(Instant expectedTime, String status) {
    }
}
```

- [ ] **Step 2: Écrire les tests unitaires d'agrégation (qui échouent)**

Créer `StationDepartureServiceTest.java` :

```java
package com.mapidf.services;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import com.mapidf.controllers.lines.DeparturesResponse;
import com.mapidf.rt.RtSnapshot.LiveJourney;
import com.mapidf.rt.RtSnapshot.LiveJourney.Call;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class StationDepartureServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-22T08:00:00Z");
    private final StationDepartureService service = new StationDepartureService();

    private static LiveJourney journey(String dest, Call... calls) {
        return new LiveJourney("STIF:Line::C01379:", "J-" + dest, "0", dest, List.of(calls));
    }

    private static Call call(String ref, Instant t) {
        return new Call(ref, t, "ON_TIME");
    }

    @Test
    void groupsByDestinationSortsByTimeAndCapsPerDirection() {
        // Station = quai "463641". Deux courses vers "Montreuil" et une vers "Pont de Sèvres".
        Set<String> keys = Set.of("463641");
        List<LiveJourney> journeys = List.of(
            journey("Montreuil", call("STIF:StopPoint:Q:463641:", NOW.plusSeconds(360))),  // 6 min
            journey("Montreuil", call("STIF:StopPoint:Q:463641:", NOW.plusSeconds(120))),  // 2 min
            journey("Montreuil", call("STIF:StopPoint:Q:463641:", NOW.plusSeconds(600))),  // 10 min
            journey("Montreuil", call("STIF:StopPoint:Q:463641:", NOW.plusSeconds(900))),  // 15 min (4e → coupé)
            journey("Pont de Sèvres", call("STIF:StopPoint:Q:463641:", NOW.plusSeconds(200))));

        DeparturesResponse r = service.departures("Havre-Caumartin", keys, journeys, NOW, 3);

        assertThat(r.stationName()).isEqualTo("Havre-Caumartin");
        assertThat(r.directions()).hasSize(2);
        DeparturesResponse.Direction montreuil = r.directions().stream()
            .filter(d -> d.destination().equals("Montreuil")).findFirst().orElseThrow();
        // trié par heure, cap à 3 : 2 / 6 / 10 min
        assertThat(montreuil.passages()).extracting(DeparturesResponse.Passage::expectedTime)
            .containsExactly(NOW.plusSeconds(120), NOW.plusSeconds(360), NOW.plusSeconds(600));
    }

    @Test
    void excludesPassagesInThePastAndCallsAtOtherStops() {
        Set<String> keys = Set.of("463641");
        List<LiveJourney> journeys = List.of(
            journey("Montreuil",
                call("STIF:StopPoint:Q:463641:", NOW.minusSeconds(60)),   // passé → exclu
                call("STIF:StopPoint:Q:463641:", NOW.plusSeconds(180))),  // futur → gardé
            journey("Montreuil", call("STIF:StopPoint:Q:999999:", NOW.plusSeconds(90)))); // autre arrêt → exclu

        DeparturesResponse r = service.departures("X", keys, journeys, NOW, 3);

        assertThat(r.directions()).hasSize(1);
        assertThat(r.directions().getFirst().passages()).hasSize(1);
    }

    @Test
    void returnsEmptyDirectionsWhenNoUpcomingPassage() {
        DeparturesResponse r = service.departures("X", Set.of("1"),
            List.of(journey("Montreuil", call("STIF:StopPoint:Q:1:", NOW.minusSeconds(10)))), NOW, 3);
        assertThat(r.directions()).isEmpty();
    }
}
```

- [ ] **Step 3: Lancer → échoue**

Run: `cd backend && ./mvnw test -Dtest=StationDepartureServiceTest`
Expected: FAIL — `StationDepartureService` inexistant (ne compile pas).

- [ ] **Step 4: Implémenter le service d'agrégation**

Créer `StationDepartureService.java` :

```java
package com.mapidf.services;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.mapidf.controllers.lines.DeparturesResponse;
import com.mapidf.controllers.lines.DeparturesResponse.Direction;
import com.mapidf.controllers.lines.DeparturesResponse.Passage;
import com.mapidf.position.PositionEngine;
import com.mapidf.rt.RtSnapshot.LiveJourney;
import org.springframework.stereotype.Service;

/**
 * Prochains passages à une station, agrégés depuis le snapshot temps réel déjà en mémoire
 * (aucun appel PRIM). Un passage = un {@code Call} futur d'une course, dont l'arrêt appartient
 * à la station (match par {@link PositionEngine#stopKey}). Regroupé par destination.
 */
@Service
public class StationDepartureService {

    public DeparturesResponse departures(String stationName, Set<String> stopKeys,
                                         List<LiveJourney> journeys, Instant now, int perDirection) {
        // destination -> passages futurs à cette station, dans l'ordre d'insertion des destinations
        Map<String, List<Passage>> byDestination = new LinkedHashMap<>();
        for (LiveJourney journey : journeys) {
            for (LiveJourney.Call call : journey.calls()) {
                if (call.time() == null || call.time().isBefore(now)) {
                    continue;
                }
                if (!stopKeys.contains(PositionEngine.stopKey(call.stopRef()))) {
                    continue;
                }
                byDestination.computeIfAbsent(journey.destination(), k -> new ArrayList<>())
                    .add(new Passage(call.time(), call.departureStatus()));
            }
        }

        List<Direction> directions = new ArrayList<>();
        byDestination.forEach((destination, passages) -> {
            List<Passage> sorted = passages.stream()
                .sorted(Comparator.comparing(Passage::expectedTime))
                .limit(perDirection)
                .toList();
            directions.add(new Direction(destination, sorted));
        });
        return new DeparturesResponse(stationName, directions);
    }
}
```

- [ ] **Step 5: Lancer → vert**

Run: `cd backend && ./mvnw test -Dtest=StationDepartureServiceTest`
Expected: PASS (3 tests).

- [ ] **Step 6: Repository — résolution station → quais**

Dans `StopRepository.java` :

```java
package com.mapidf.data.repositories;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.mapidf.data.entity.Stop;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StopRepository extends JpaRepository<Stop, UUID> {

    List<Stop> findByParentStation(String parentStation);

    Optional<Stop> findByGtfsId(String gtfsId);
}
```

- [ ] **Step 7: Code d'erreur station inconnue**

Dans `ErrorCode.java`, ajouter la constante :

```java
    LINE_NOT_FOUND("Line not found"),
    STATION_NOT_FOUND("Station not found"),
    BAD_REQUEST("Invalid request"),
    INTERNAL_ERROR("Internal server error");
```

- [ ] **Step 8: Écrire le test d'endpoint (qui échoue)**

Créer `LineControllerDeparturesIT.java` :

```java
package com.mapidf.controllers.lines;

import com.mapidf.MapIdfTest;
import com.mapidf.gtfs.GtfsStaticLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@MapIdfTest
class LineControllerDeparturesIT {

    @Autowired WebApplicationContext wac;
    @Autowired GtfsStaticLoader loader;
    MockMvc mockMvc;

    @BeforeEach
    void setup() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).build();
        try (var in = getClass().getResourceAsStream("/gtfs-parent.zip")) {
            loader.loadFromZip(in, "RP");
        }
    }

    @Test
    void unknownStationReturns404() throws Exception {
        mockMvc.perform(get("/lines/9/stations/INCONNU/departures"))
            .andExpect(status().isNotFound());
    }

    @Test
    void knownStationWithoutLiveDataReturnsEmptyDirections() throws Exception {
        // Pas de snapshot temps réel injecté → station connue (SAA) mais aucune direction.
        mockMvc.perform(get("/lines/9/stations/SAA/departures"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.stationName").value("Alpha"))
            .andExpect(jsonPath("$.directions.length()").value(0));
    }
}
```

- [ ] **Step 9: Lancer → échoue**

Run: `cd backend && ./mvnw test -Dtest=LineControllerDeparturesIT`
Expected: FAIL — pas de mapping `/stations/{stationId}/departures` (404 de routing ou erreur), assertions non satisfaites.

- [ ] **Step 10: Câbler l'endpoint dans `LineController`**

Ajouter les dépendances injectées et l'endpoint. Champs (après `lineProperties`) :

```java
    private final StopRepository stopRepository;
    private final StationDepartureService departureService;
```

Imports à ajouter :

```java
import java.util.ArrayList;
import java.util.Set;
import java.util.stream.Collectors;
import com.mapidf.data.entity.Stop;
import com.mapidf.data.enums.ErrorCode;
import com.mapidf.data.repositories.StopRepository;
import com.mapidf.exceptions.ApiException;
import com.mapidf.services.StationDepartureService;
import org.springframework.http.HttpStatus;
```

Méthode (après `vehicles`) :

```java
    @GetMapping("/{id}/stations/{stationId}/departures")
    public DeparturesResponse departures(@PathVariable String id, @PathVariable String stationId) {
        // Résout la station → ses quais : soit par parent_station, soit un arrêt seul (gtfs_id).
        List<Stop> platforms = new ArrayList<>(stopRepository.findByParentStation(stationId));
        stopRepository.findByGtfsId(stationId).ifPresent(platforms::add);
        if (platforms.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, ErrorCode.STATION_NOT_FOUND);
        }
        Set<String> stopKeys = platforms.stream()
            .map(s -> PositionEngine.stopKey(s.getGtfsId()))
            .collect(Collectors.toSet());
        // MVP mono-ligne : on n'agrège que les courses de la ligne configurée.
        return departureService.departures(
            platforms.getFirst().getName(),
            stopKeys,
            poller.current().forLine(lineProperties.siriLineRef()),
            Instant.now(),
            3);
    }
```

- [ ] **Step 11: Lancer → vert**

Run: `cd backend && ./mvnw test -Dtest=LineControllerDeparturesIT`
Expected: PASS (2 tests).

- [ ] **Step 12: Suite backend complète**

Run: `cd backend && ./mvnw verify`
Expected: BUILD SUCCESS, tous les tests verts.

- [ ] **Step 13: Commit**

```bash
git add backend/src/main/java/com/mapidf/controllers/lines/DeparturesResponse.java \
        backend/src/main/java/com/mapidf/services/StationDepartureService.java \
        backend/src/test/java/com/mapidf/services/StationDepartureServiceTest.java \
        backend/src/test/java/com/mapidf/controllers/lines/LineControllerDeparturesIT.java \
        backend/src/main/java/com/mapidf/data/repositories/StopRepository.java \
        backend/src/main/java/com/mapidf/data/enums/ErrorCode.java \
        backend/src/main/java/com/mapidf/controllers/lines/LineController.java
git commit -m "feat(rt): endpoint prochains passages à une station (agrégé depuis le snapshot)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 4: Front — stations regroupées, labels, curseur, clic → API

**Files:**
- Modify: `frontend/src/api/types.ts`
- Modify: `frontend/src/api/lines.ts`
- Modify: `frontend/src/map/useLineShape.ts`

**Interfaces:**
- Consumes: `/shape` `stops[].platformIds`, endpoint departures.
- Produces: type `DeparturesResponse`, `fetchDepartures(lineId, stationId) : Promise<DeparturesResponse>`, propriété `id` sur les features de la couche `stops` (pour le clic), couche `stops-labels`, curseur `pointer` sur `stops`.

- [ ] **Step 1: Types**

Dans `types.ts`, mettre à jour `stops` et ajouter `DeparturesResponse` :

```typescript
export interface ShapeResponse {
  lineId: string;
  color: string;
  shape: [number, number][];
  stops: { id: string; name: string; lat: number; lng: number; platformIds: string[] }[];
}

export interface DeparturesResponse {
  stationName: string;
  directions: {
    destination: string;
    passages: { expectedTime: string; status: string }[];
  }[];
}
```

- [ ] **Step 2: Client API**

Dans `lines.ts`, ajouter :

```typescript
import { API_BASE } from "./config";
import type { ShapeResponse, VehiclesResponse, DeparturesResponse } from "./types";
```

et la fonction :

```typescript
export async function fetchDepartures(lineId: string, stationId: string): Promise<DeparturesResponse> {
  const response = await fetch(`${API_BASE}/lines/${lineId}/stations/${encodeURIComponent(stationId)}/departures`);
  if (!response.ok) {
    throw new Error(`departures ${response.status}`);
  }
  return response.json();
}
```

- [ ] **Step 3: `useLineShape` — id sur les stations, labels, curseur**

Dans `useLineShape.ts`, la source `stops` porte `id` et `name` ; ajouter une couche labels et le curseur. Remplacer le bloc source/layer `stops` (lignes ~35-56) par :

```typescript
        map.addSource("stops", {
          type: "geojson",
          data: {
            type: "FeatureCollection",
            features: shape.stops.map((s) => ({
              type: "Feature",
              properties: { id: s.id, name: s.name },
              geometry: { type: "Point", coordinates: [s.lng, s.lat] },
            })),
          },
        });
        map.addLayer({
          id: "stops",
          type: "circle",
          source: "stops",
          paint: {
            "circle-radius": 5,
            "circle-color": "#fff",
            "circle-stroke-color": shape.color,
            "circle-stroke-width": 2,
          },
        });
        // Noms affichés seulement en zoom rapproché (collision gérée par MapLibre) → pas
        // d'encombrement au dézoom, coût maîtrisé même avec beaucoup de stations.
        map.addLayer({
          id: "stops-labels",
          type: "symbol",
          source: "stops",
          minzoom: 13,
          layout: {
            "text-field": ["get", "name"],
            "text-size": 12,
            "text-offset": [0, 1.2],
            "text-anchor": "top",
          },
          paint: {
            "text-color": "#111",
            "text-halo-color": "#fff",
            "text-halo-width": 1.5,
          },
        });
        // Curseur main au survol des stations cliquables.
        map.on("mouseenter", "stops", () => { map.getCanvas().style.cursor = "pointer"; });
        map.on("mouseleave", "stops", () => { map.getCanvas().style.cursor = ""; });
```

- [ ] **Step 4: Build**

Run: `cd frontend && npm run build`
Expected: build OK (pas d'erreur TS).

- [ ] **Step 5: Commit**

```bash
git add frontend/src/api/types.ts frontend/src/api/lines.ts frontend/src/map/useLineShape.ts
git commit -m "feat(front): stations regroupées avec noms (zoom) et curseur cliquable

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 5: Front — panneau prochains passages + sélection exclusive

**Files:**
- Create: `frontend/src/ui/formatEta.ts`
- Create: `frontend/src/ui/StopPanel.tsx`
- Modify: `frontend/src/ui/VehiclePanel.tsx`
- Modify: `frontend/src/App.tsx`

**Interfaces:**
- Consumes: `fetchDepartures`, `DeparturesResponse`.
- Produces: module partagé `formatEta(expectedTime, opts?) : string` ; composant `StopPanel({ data, onClose })`. État `App` : `selectedStation` exclusif de `selectedTripId`.

- [ ] **Step 1: Module partagé `formatEta`**

`formatEta` existe aujourd'hui en double (inline dans `VehiclePanel`, prévu dans `StopPanel`). On l'extrait dans un module unique. Les deux usages diffèrent sur la granularité : le panneau train (rafraîchi à chaque poll) affiche les secondes ; la liste de passages (fetch unique au clic, non rafraîchie) reste en minutes pour ne pas figer des secondes trompeuses. D'où l'option `withSeconds`.

Créer `frontend/src/ui/formatEta.ts` :

```typescript
export function formatEta(expectedTime: string, opts: { withSeconds?: boolean } = {}): string {
  const sec = Math.round((new Date(expectedTime).getTime() - Date.now()) / 1000);
  if (Number.isNaN(sec)) {
    return "—";
  }
  if (sec <= 0) {
    return opts.withSeconds ? "imminent / à quai" : "imminent";
  }
  if (sec < 60) {
    return `dans ${sec} s`;
  }
  const min = Math.floor(sec / 60);
  return opts.withSeconds ? `dans ${min} min ${sec % 60} s` : `dans ${min} min`;
}
```

- [ ] **Step 2: `VehiclePanel` utilise le module partagé**

Dans `VehiclePanel.tsx`, supprimer la fonction `formatEta` locale (lignes ~8-20) et l'importer :

```tsx
import { formatEta } from "./formatEta";
```

Remplacer l'appel existant par la version avec secondes :

```tsx
        Arrivée estimée : <b>{formatEta(vehicle.expectedTime, { withSeconds: true })}</b>
```

- [ ] **Step 3: Composant `StopPanel`**

Créer `StopPanel.tsx` :

```tsx
import type { DeparturesResponse } from "../api/types";
import { formatEta } from "./formatEta";

interface Props {
  data: DeparturesResponse | null;
  onClose: () => void;
}

export function StopPanel({ data, onClose }: Props) {
  if (!data) {
    return null;
  }
  return (
    <div
      style={{
        position: "absolute",
        top: 12,
        right: 12,
        width: 260,
        padding: 16,
        background: "#fff",
        borderRadius: 8,
        boxShadow: "0 2px 12px rgba(0,0,0,.2)",
        font: "14px sans-serif",
      }}
    >
      <button
        onClick={onClose}
        style={{ float: "right", border: "none", background: "none", cursor: "pointer", fontSize: 20, lineHeight: 1, padding: 4 }}
        aria-label="Fermer"
      >
        ✕
      </button>
      <h3 style={{ margin: "0 0 8px" }}>{data.stationName}</h3>
      {data.directions.length === 0 && (
        <p style={{ margin: "4px 0", color: "#666" }}>Aucun passage annoncé.</p>
      )}
      {data.directions.map((dir) => (
        <div key={dir.destination} style={{ margin: "8px 0 0" }}>
          <p style={{ margin: "0 0 2px", fontWeight: 600 }}>→ {dir.destination}</p>
          <ul style={{ margin: "0 0 0 16px", padding: 0 }}>
            {dir.passages.map((p, i) => (
              <li key={i}>{formatEta(p.expectedTime)}</li>
            ))}
          </ul>
        </div>
      ))}
    </div>
  );
}
```

- [ ] **Step 4: État exclusif + clic station dans `App.tsx`**

Ajouter l'import et le type :

```tsx
import { StopPanel } from "./ui/StopPanel";
import { fetchDepartures } from "./api/lines";
import type { VehiclesResponse, DeparturesResponse } from "./api/types";
```

Ajouter l'état (après `follow`) :

```tsx
  const [station, setStation] = useState<DeparturesResponse | null>(null);
```

Étendre `clearSelection` et ajouter la sélection de station exclusive. Remplacer le handler de clic véhicule pour qu'il ferme la station, et ajouter un handler de clic station. Dans le premier `useEffect` (clic), après `map.on("click", "vehicles", onClick);` ajouter :

```tsx
    const onStationClick = async (e: maplibregl.MapLayerMouseEvent) => {
      const id = e.features?.[0]?.properties?.id as string | undefined;
      if (!id) {
        return;
      }
      // Sélection exclusive : ouvrir une station ferme le suivi d'un train.
      setSelected(null);
      setSelectedTripId(null);
      setFollow(false);
      try {
        setStation(await fetchDepartures(LINE_ID, id));
      } catch {
        setStation(null);
      }
    };
    map.on("click", "stops", onStationClick);
```

et dans le `return` du même effect, ajouter `map.off("click", "stops", onStationClick);`.

Dans `onClick` (clic véhicule), ajouter en première ligne `setStation(null);` (ferme la station si on sélectionne un train).

Rendre le panneau. Dans le JSX de retour, après `<VehiclePanel ... />` ajouter :

```tsx
      <StopPanel data={station} onClose={() => setStation(null)} />
```

- [ ] **Step 5: Build**

Run: `cd frontend && npm run build`
Expected: build OK.

- [ ] **Step 6: Contrôle visuel**

Vérifier (via l'app lancée par l'utilisateur) : clic sur une station → panneau passages ; clic sur un train → le panneau station se ferme ; ✕ ferme ; le panneau train affiche toujours l'ETA en min + s.

- [ ] **Step 7: Commit**

```bash
git add frontend/src/ui/formatEta.ts frontend/src/ui/StopPanel.tsx frontend/src/ui/VehiclePanel.tsx frontend/src/App.tsx
git commit -m "feat(front): panneau prochains passages, exclusif du panneau train

formatEta extrait dans un module partagé (option withSeconds).

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 6: Front — véhicules directionnels (flèche couleur ligne + halo sélection)

**Files:**
- Modify: `frontend/src/map/VehicleLayer.ts`
- Modify: `frontend/src/map/useVehicles.ts`
- Modify: `frontend/src/App.tsx`

**Interfaces:**
- Consumes: `shape.color` (couleur ligne).
- Produces: `VehicleLayer` construit avec une couleur ; couche cliquable `vehicles` (symbole flèche), couche `vehicles-halo` (anneau bleu, feature sélectionnée). `useVehicles(map, lineId, color, selectedTripId, follow, onSelected?)`.

- [ ] **Step 1: Icône flèche + couches dans `VehicleLayer`**

Dans `VehicleLayer.ts` : ajouter un paramètre `color` au constructeur, générer l'icône flèche une fois, et remplacer la couche circle par halo (circle, sélection) + symbole flèche.

Constructeur :

```typescript
  constructor(
    private map: MlMap,
    private durationMs: number,
    private color: string,
  ) {
    this.ensureLayer();
  }
```

Générer l'icône (méthode privée) — triangle plein pointant vers le haut (nord à rotation 0), teinté couleur ligne :

```typescript
  private arrowImage(): ImageData {
    const size = 24;
    const canvas = document.createElement("canvas");
    canvas.width = size;
    canvas.height = size;
    const ctx = canvas.getContext("2d")!;
    ctx.fillStyle = this.color;
    ctx.strokeStyle = "#fff";
    ctx.lineWidth = 2;
    ctx.beginPath();
    ctx.moveTo(size / 2, 2);        // pointe (haut = nord)
    ctx.lineTo(size - 4, size - 4); // bas droite
    ctx.lineTo(4, size - 4);        // bas gauche
    ctx.closePath();
    ctx.fill();
    ctx.stroke();
    return ctx.getImageData(0, 0, size, size);
  }
```

Dans `ensureLayer().add`, après avoir ajouté la source `vehicles`, remplacer l'ancienne couche `vehicles` (circle) par :

```typescript
      if (!this.map.hasImage("vehicle-arrow")) {
        this.map.addImage("vehicle-arrow", this.arrowImage());
      }
      // Halo de sélection SOUS les flèches : anneau bleu, uniquement la feature sélectionnée.
      this.map.addLayer({
        id: "vehicles-halo",
        type: "circle",
        source: "vehicles",
        filter: ["==", ["get", "selected"], true],
        paint: {
          "circle-radius": 12,
          "circle-color": "rgba(29,78,216,0.15)",
          "circle-stroke-color": "#1d4ed8",
          "circle-stroke-width": 3,
        },
      });
      // Flèches orientées sur le bearing (0 = nord), alignées à la carte.
      this.map.addLayer({
        id: "vehicles",
        type: "symbol",
        source: "vehicles",
        layout: {
          "icon-image": "vehicle-arrow",
          "icon-rotate": ["get", "bearing"],
          "icon-rotation-alignment": "map",
          "icon-allow-overlap": true,
          "icon-size": 0.8,
        },
      });
```

(La logique rAF, `setData` et `jumpTo` restent inchangées : la couche flèche et le halo lisent la même source `vehicles` mise à jour par frame ; aucune couche n'est reconstruite.)

- [ ] **Step 2: `useVehicles` passe la couleur**

Dans `useVehicles.ts`, ajouter `color` à la signature et à la construction :

```typescript
export function useVehicles(
  map: MlMap | null,
  lineId: string,
  color: string,
  selectedTripId: string | null = null,
  follow = false,
  onSelected?: (vehicle: V | null) => void,
) {
```

et :

```typescript
    const layer = new VehicleLayer(map, VEHICLE_POLL_MS, color);
```

Ajouter `color` aux dépendances de l'effet principal : `}, [map, lineId, color]);`.

- [ ] **Step 3: `App.tsx` fournit la couleur**

La couleur vient de `/shape`. `useLineShape` ne la renvoie pas aujourd'hui ; on récupère la couleur via un petit fetch dédié dans `App`. Ajouter un état et un effet :

```tsx
import { fetchShape, fetchDepartures } from "./api/lines";
```

```tsx
  const [lineColor, setLineColor] = useState("#e30613");
  useEffect(() => {
    fetchShape(LINE_ID).then((s) => setLineColor(s.color)).catch(() => {});
  }, []);
```

Passer la couleur à `useVehicles` :

```tsx
  useVehicles(map, LINE_ID, lineColor, selectedTripId, follow, (v) => {
    if (v) {
      setSelected(toSelected(v));
    }
  });
```

> Note : `/shape` a un `Cache-Control` de 10 min ; ce second fetch est servi par le cache navigateur (pas de coût réseau réel). `useLineShape` garde sa propre requête inchangée.

- [ ] **Step 3b: Curseur `pointer` sur les véhicules**

La couche cliquable `vehicles` est créée dans `VehicleLayer` (Task 6 Step 1). Ajouter le curseur main dans le `useEffect` de clic d'`App.tsx` (qui a déjà un cleanup `map.off`). Après `map.on("click", "stops", onStationClick);` :

```tsx
    const enter = () => { map.getCanvas().style.cursor = "pointer"; };
    const leave = () => { map.getCanvas().style.cursor = ""; };
    map.on("mouseenter", "vehicles", enter);
    map.on("mouseleave", "vehicles", leave);
```

et dans le `return` du même effect :

```tsx
      map.off("mouseenter", "vehicles", enter);
      map.off("mouseleave", "vehicles", leave);
```

- [ ] **Step 4: Build**

Run: `cd frontend && npm run build`
Expected: build OK.

- [ ] **Step 5: Contrôle visuel**

Vérifier : trains = flèches jaunes orientées dans le sens de marche ; train sélectionné = anneau bleu ; suivi caméra toujours fonctionnel.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/map/VehicleLayer.ts frontend/src/map/useVehicles.ts frontend/src/App.tsx
git commit -m "feat(front): véhicules en flèches directionnelles couleur ligne + halo sélection

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 7: Front — gains rapides (bouton nord, ✕ agrandie, légende + compteur)

**Files:**
- Create: `frontend/src/ui/Legend.tsx`
- Modify: `frontend/src/map/MapView.tsx`
- Modify: `frontend/src/map/useVehicles.ts`
- Modify: `frontend/src/ui/VehiclePanel.tsx`
- Modify: `frontend/src/App.tsx`

**Interfaces:**
- Consumes: nombre de véhicules du dernier poll.
- Produces: `NavigationControl` haut-gauche ; composant `Legend({ color, count })` bas-gauche ; callback `onCount` de `useVehicles` ; ✕ agrandie dans `VehiclePanel`.

- [ ] **Step 1: Bouton nord (NavigationControl)**

Dans `MapView.tsx`, après la création de l'instance `new maplibregl.Map({...})`, ajouter :

```typescript
      ref.current.instance.addControl(
        new maplibregl.NavigationControl({ showCompass: true, visualizePitch: true }),
        "top-left",
      );
```

- [ ] **Step 2: Compteur remonté par `useVehicles`**

Dans `useVehicles.ts`, ajouter un paramètre `onCount?: (n: number) => void` en fin de signature, un ref, et l'appel dans le tick :

```typescript
  onCount?: (n: number) => void,
) {
```

```typescript
  const onCountRef = useRef(onCount);
  onCountRef.current = onCount;
```

Dans `tick`, après `layer.update(...)` :

```typescript
        onCountRef.current?.(response.vehicles.length);
```

- [ ] **Step 3: Composant `Legend`**

Créer `Legend.tsx` :

```tsx
interface Props {
  color: string;
  count: number;
}

export function Legend({ color, count }: Props) {
  return (
    <div
      style={{
        position: "absolute",
        bottom: 12,
        left: 12,
        padding: "10px 12px",
        background: "#fff",
        borderRadius: 8,
        boxShadow: "0 2px 12px rgba(0,0,0,.2)",
        font: "13px sans-serif",
      }}
    >
      <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
        <span style={{ width: 12, height: 12, borderRadius: "50%", background: color, border: "2px solid #fff", boxShadow: "0 0 0 1px #ccc" }} />
        <b>{count} trains en circulation</b>
      </div>
      <div style={{ color: "#666", marginTop: 4 }}>Position estimée (pas de GPS en métro).</div>
    </div>
  );
}
```

- [ ] **Step 4: ✕ agrandie dans `VehiclePanel`**

Dans `VehiclePanel.tsx`, remplacer le style du bouton de fermeture par une zone de clic plus grande :

```tsx
      <button
        onClick={onClose}
        style={{ float: "right", border: "none", background: "none", cursor: "pointer", fontSize: 20, lineHeight: 1, padding: 4 }}
        aria-label="Fermer"
      >
        ✕
      </button>
```

- [ ] **Step 5: Câbler compteur + légende dans `App.tsx`**

Ajouter l'import et l'état :

```tsx
import { Legend } from "./ui/Legend";
```

```tsx
  const [count, setCount] = useState(0);
```

Ajouter `setCount` en dernier argument de `useVehicles` :

```tsx
  useVehicles(map, LINE_ID, lineColor, selectedTripId, follow, (v) => {
    if (v) {
      setSelected(toSelected(v));
    }
  }, setCount);
```

Rendre la légende dans le `return`, après `<StopPanel ... />` :

```tsx
      <Legend color={lineColor} count={count} />
```

- [ ] **Step 6: Build**

Run: `cd frontend && npm run build`
Expected: build OK.

- [ ] **Step 7: Contrôle visuel**

Vérifier : boussole en haut-gauche (remet au nord au clic) ; légende + compteur en bas-gauche ; ✕ plus grande et facile à cliquer.

- [ ] **Step 8: Commit**

```bash
git add frontend/src/ui/Legend.tsx frontend/src/map/MapView.tsx frontend/src/map/useVehicles.ts \
        frontend/src/ui/VehiclePanel.tsx frontend/src/App.tsx
git commit -m "feat(front): bouton nord, légende + compteur de trains, croix de fermeture agrandie

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 8: Vérification finale

- [ ] **Step 1: Backend complet**

Run: `cd backend && ./mvnw verify`
Expected: BUILD SUCCESS.

- [ ] **Step 2: Frontend complet**

Run: `cd frontend && npm run build`
Expected: build OK.

- [ ] **Step 3: Contrôle visuel de bout en bout (utilisateur)**

Sur l'app lancée par l'utilisateur : stations regroupées (un point par station), noms au zoom, clic station → passages, flèches directionnelles, halo de suivi, boussole, légende/compteur, ✕ agrandie.

- [ ] **Step 4: Finalisation de branche**

REQUIRED SUB-SKILL: superpowers:finishing-a-development-branch (vérifier tests → présenter options merge/PR).

---

# Addendum — finitions post-revue visuelle (Tasks 9-11)

Retours visuels utilisateur après la 1ʳᵉ passe (branche non encore mergée) :
1. Trains peu distinguables du tracé (même couleur) → atténuer le tracé.
2. Mettre en valeur l'arrêt sélectionné + centrer la caméra.
3. Cliquer un passage dans la card → suivre le métro correspondant (nécessite `journeyRef`).
4. Ordre des directions non uniforme entre arrêts → ordonner déterministiquement.
5. Noms d'arrêts jamais visibles → BUG : couche `symbol` sans `text-font` (le style Liberty
   ne fournit que `Noto Sans *`, la police par défaut `Open Sans` renvoie 404 → texte non rendu).

## Task 9: Backend — ordre déterministe des directions + journeyRef dans les passages

**Files:**
- Modify: `backend/src/main/java/com/mapidf/controllers/lines/DeparturesResponse.java`
- Modify: `backend/src/main/java/com/mapidf/services/StationDepartureService.java`
- Test: `backend/src/test/java/com/mapidf/services/StationDepartureServiceTest.java`

**Interfaces:**
- Consumes: `LiveJourney.directionRef()`, `LiveJourney.journeyRef()`, `LiveJourney.destination()`.
- Produces: `DeparturesResponse.Passage(String journeyRef, Instant expectedTime, String status)` ; directions triées par `(directionRef, destination)` (ordre stable par ligne, `directionRef` null normalisé en "").

- [ ] **Step 1: Étendre les tests (RED)**

Dans `StationDepartureServiceTest.java`, adapter le helper `journey` pour porter un `directionRef` et ajouter deux assertions. Remplacer le helper existant `journey(String dest, Call... calls)` par :

```java
    private static LiveJourney journey(String dest, Call... calls) {
        return journey(dest, "0", dest, calls);
    }

    private static LiveJourney journey(String dest, String directionRef, String journeyRef, Call... calls) {
        return new LiveJourney("STIF:Line::C01379:", journeyRef, directionRef, dest, List.of(calls));
    }
```

Dans `groupsByDestinationSortsByTimeAndCapsPerDirection`, après les assertions existantes, ajouter :

```java
        // journeyRef propagé sur chaque passage (permet le clic → suivi côté front)
        assertThat(montreuil.passages()).allSatisfy(p -> assertThat(p.journeyRef()).isNotBlank());
```

Ajouter un nouveau test :

```java
    @Test
    void ordersDirectionsByDirectionRefThenDestination() {
        // Peu importe l'ordre du flux : direction 0 (Montreuil) avant direction 1 (Pont de Sèvres).
        List<LiveJourney> journeys = List.of(
            journey("Pont de Sèvres", "1", "jA", call("STIF:StopPoint:Q:1:", NOW.plusSeconds(120))),
            journey("Mairie de Montreuil", "0", "jB", call("STIF:StopPoint:Q:1:", NOW.plusSeconds(60))));

        DeparturesResponse r = service.departures("X", Set.of("1"), journeys, NOW, 3);

        assertThat(r.directions()).extracting(DeparturesResponse.Direction::destination)
            .containsExactly("Mairie de Montreuil", "Pont de Sèvres");
    }
```

- [ ] **Step 2: Lancer → échoue**

Run: `cd backend && JAVA_HOME=/home/abodet/.jdks/temurin-25.0.3 ./mvnw test -Dtest=StationDepartureServiceTest`
Expected: FAIL — `Passage` n'a pas de `journeyRef` (ne compile pas) / ordre non garanti.

- [ ] **Step 3: Ajouter `journeyRef` au DTO**

Dans `DeparturesResponse.java` :

```java
    public record Passage(String journeyRef, Instant expectedTime, String status) {
    }
```

- [ ] **Step 4: Service — journeyRef + ordre déterministe**

Remplacer le corps de `StationDepartureService.departures` par :

```java
    public DeparturesResponse departures(String stationName, Set<String> stopKeys,
                                         List<LiveJourney> journeys, Instant now, int perDirection) {
        // destination -> passages futurs à cette station (ordre d'insertion, retrié ensuite)
        Map<String, List<Passage>> byDestination = new LinkedHashMap<>();
        // destination -> directionRef (sens SIRI), pour un ordre d'affichage stable par ligne
        Map<String, String> directionByDestination = new HashMap<>();
        for (LiveJourney journey : journeys) {
            for (LiveJourney.Call call : journey.calls()) {
                if (call.time() == null || call.time().isBefore(now)) {
                    continue;
                }
                if (!stopKeys.contains(PositionEngine.stopKey(call.stopRef()))) {
                    continue;
                }
                String destination = journey.destination();
                byDestination.computeIfAbsent(destination, k -> new ArrayList<>())
                    .add(new Passage(journey.journeyRef(), call.time(), call.departureStatus()));
                directionByDestination.putIfAbsent(destination,
                    journey.directionRef() == null ? "" : journey.directionRef());
            }
        }

        return new DeparturesResponse(stationName, byDestination.entrySet().stream()
            .sorted(Comparator
                .comparing((Map.Entry<String, List<Passage>> e) -> directionByDestination.get(e.getKey()))
                .thenComparing(Map.Entry::getKey))
            .map(e -> new Direction(e.getKey(), e.getValue().stream()
                .sorted(Comparator.comparing(Passage::expectedTime))
                .limit(perDirection)
                .toList()))
            .toList());
    }
```

Ajouter l'import manquant : `import java.util.HashMap;`

- [ ] **Step 5: Lancer → vert**

Run: `cd backend && JAVA_HOME=/home/abodet/.jdks/temurin-25.0.3 ./mvnw test -Dtest=StationDepartureServiceTest`
Expected: PASS (4 tests).

- [ ] **Step 6: Suite backend complète**

Run: `cd backend && JAVA_HOME=/home/abodet/.jdks/temurin-25.0.3 ./mvnw verify`
Expected: BUILD SUCCESS (l'IT departures `directions: []` reste vert : pas de snapshot → aucune direction).

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/mapidf/controllers/lines/DeparturesResponse.java \
        backend/src/main/java/com/mapidf/services/StationDepartureService.java \
        backend/src/test/java/com/mapidf/services/StationDepartureServiceTest.java
git commit -m "feat(rt): ordre déterministe des directions + journeyRef par passage

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

## Task 10: Front — atténuer le tracé (#1) + corriger les noms d'arrêts (#5)

**Files:**
- Modify: `frontend/src/map/useLineShape.ts`

**Interfaces:**
- Produces: tracé `line-shape` à opacité réduite ; couche `stops-labels` avec `text-font` valide et seuil de zoom abaissé.

- [ ] **Step 1: Atténuer le tracé**

Dans `useLineShape.ts`, la couche `line-shape`, ajouter `line-opacity` au paint :

```typescript
        map.addLayer({
          id: "line-shape",
          type: "line",
          source: "line-shape",
          paint: { "line-color": shape.color, "line-width": 4, "line-opacity": 0.45 },
        });
```

- [ ] **Step 2: Corriger les labels (police + seuil)**

Dans la couche `stops-labels`, régler `minzoom` à 12 et ajouter `text-font` (police fournie par le style OpenFreeMap Liberty ; sans elle, les glyphes par défaut renvoient 404 et le texte ne s'affiche jamais) :

```typescript
        map.addLayer({
          id: "stops-labels",
          type: "symbol",
          source: "stops",
          minzoom: 12,
          layout: {
            "text-field": ["get", "name"],
            "text-font": ["Noto Sans Regular"],
            "text-size": 12,
            "text-offset": [0, 1.2],
            "text-anchor": "top",
          },
          paint: {
            "text-color": "#111",
            "text-halo-color": "#fff",
            "text-halo-width": 1.5,
          },
        });
```

- [ ] **Step 3: Build**

Run: `cd frontend && npm run build`
Expected: build OK.

- [ ] **Step 4: Contrôle visuel (utilisateur)**

Les flèches ressortent nettement du tracé (tracé estompé) ; les noms de stations s'affichent à partir du zoom 12.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/map/useLineShape.ts
git commit -m "fix(front): tracé atténué + noms d'arrêts (text-font Noto Sans, seuil zoom 12)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

## Task 11: Front — arrêt sélectionné mis en valeur + centrage (#2) + clic passage → suivi (#3)

**Files:**
- Modify: `frontend/src/api/types.ts`
- Modify: `frontend/src/map/useLineShape.ts`
- Modify: `frontend/src/ui/StopPanel.tsx`
- Modify: `frontend/src/App.tsx`

**Interfaces:**
- Consumes: `journeyRef` dans les passages (Task 9), couche `stops` avec propriété `id`.
- Produces: couche `stops-selected` (anneau bleu, filtrée par id) ; `StopPanel` avec passages cliquables (`onSelectTrain(tripId)`) ; `App` centre la caméra + surligne l'arrêt au clic, et sélectionne/suit le train au clic d'un passage.

- [ ] **Step 1: `journeyRef` dans le type front**

Dans `types.ts`, `DeparturesResponse` :

```typescript
export interface DeparturesResponse {
  stationName: string;
  directions: {
    destination: string;
    passages: { journeyRef: string; expectedTime: string; status: string }[];
  }[];
}
```

- [ ] **Step 2: Couche de surlignage de l'arrêt sélectionné**

Dans `useLineShape.ts`, après la couche `stops-labels`, ajouter une couche anneau filtrée (invisible au départ) :

```typescript
        // Anneau de mise en valeur de l'arrêt sélectionné (piloté par setFilter depuis App).
        map.addLayer({
          id: "stops-selected",
          type: "circle",
          source: "stops",
          filter: ["==", ["get", "id"], "__none__"],
          paint: {
            "circle-radius": 10,
            "circle-color": "rgba(29,78,216,0.15)",
            "circle-stroke-color": "#1d4ed8",
            "circle-stroke-width": 3,
          },
        });
```

- [ ] **Step 3: `StopPanel` — passages cliquables**

Dans `StopPanel.tsx`, ajouter la prop `onSelectTrain` et rendre chaque passage cliquable :

```tsx
interface Props {
  data: DeparturesResponse | null;
  onClose: () => void;
  onSelectTrain?: (tripId: string) => void;
}

export function StopPanel({ data, onClose, onSelectTrain }: Props) {
```

Remplacer la liste des passages par des boutons cliquables :

```tsx
          <ul style={{ margin: "0 0 0 16px", padding: 0, listStyle: "none" }}>
            {dir.passages.map((p, i) => (
              <li key={i}>
                <button
                  onClick={() => onSelectTrain?.(p.journeyRef)}
                  style={{
                    border: "none", background: "none", padding: "2px 0", cursor: "pointer",
                    font: "inherit", color: "#1d4ed8", textAlign: "left", width: "100%",
                  }}
                  title="Suivre ce métro"
                >
                  {formatEta(p.expectedTime)}
                </button>
              </li>
            ))}
          </ul>
```

- [ ] **Step 4: `App.tsx` — centrage + surlignage + clic passage**

Dans `App.tsx`, factoriser la fermeture de station (avec effacement de l'anneau) et brancher le tout.

Ajouter un helper de fermeture station (avant le `return`), qui efface aussi l'anneau :

```tsx
  const closeStation = () => {
    setStation(null);
    map?.setFilter("stops-selected", ["==", ["get", "id"], "__none__"]);
  };
```

Dans `onStationClick`, après avoir résolu `id`, surligner + centrer, et n'ouvrir la card qu'après succès :

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
      try {
        setStation(await fetchDepartures(LINE_ID, id));
      } catch {
        setStation(null);
      }
    };
```

Dans `onClick` (clic véhicule), remplacer `setStation(null);` par la fermeture complète de la station (efface aussi l'anneau) :

```tsx
      setStation(null);
      map.setFilter("stops-selected", ["==", ["get", "id"], "__none__"]);
```

Ajouter le handler de sélection d'un train depuis la card, et le passer à `StopPanel`. Handler (près de `clearSelection`) :

```tsx
  const followTrainFromPanel = (tripId: string) => {
    closeStation();
    setSelected(null);
    setSelectedTripId(tripId);
    setFollow(true);
  };
```

Mettre à jour le rendu de `StopPanel` :

```tsx
      <StopPanel data={station} onClose={closeStation} onSelectTrain={followTrainFromPanel} />
```

> Note : le panneau train (`selected`) se remplit au prochain poll (≤ 4 s) via le callback de `useVehicles` ; en attendant, la carte surligne et centre déjà le train suivi (halo + `jumpTo`). Comportement acceptable, pas de donnée véhicule disponible dans la réponse `departures`.

- [ ] **Step 5: Build**

Run: `cd frontend && npm run build`
Expected: build OK.

- [ ] **Step 6: Contrôle visuel (utilisateur)**

Clic station → anneau bleu sur l'arrêt + recentrage + card ; clic sur un passage → la card se ferme, le métro correspondant est suivi (halo + caméra) ; clic véhicule ou ✕ → l'anneau station disparaît.

- [ ] **Step 7: Commit**

```bash
git add frontend/src/api/types.ts frontend/src/map/useLineShape.ts frontend/src/ui/StopPanel.tsx frontend/src/App.tsx
git commit -m "feat(front): arrêt sélectionné surligné + centré, clic passage → suivi du métro

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

# Addendum 2 — bugs panneau passages + taille au zoom (Tasks 12-13)

Retours visuels : (#1) le panneau passages affiche des passages « imminent » fantômes
(fetch unique jamais rafraîchi → les heures vieillissent et passent dans le passé) ;
(#3) les métros gardent une taille fixe quel que soit le zoom.

## Task 12: Front — panneau passages rafraîchi + suppression des passages passés (#1)

**Files:**
- Modify: `frontend/src/ui/StopPanel.tsx`
- Modify: `frontend/src/App.tsx`

**Interfaces:**
- Consumes: `fetchDepartures`, `VEHICLE_POLL_MS`.
- Produces: `App` garde un `selectedStationId` et re-fetch les passages au rythme du poll ; `StopPanel` masque les passages déjà partis (`expectedTime <= maintenant`), replie les directions vides et affiche « Aucun passage annoncé » s'il n'en reste aucun.

- [ ] **Step 1: `StopPanel` filtre les passages passés**

Dans `StopPanel.tsx`, calculer les directions filtrées avant le rendu (passages strictement futurs, directions non vides), et baser l'état vide dessus. Remplacer le corps de `StopPanel` (à partir du `if (!data)` jusqu'à la fin du composant) par :

```tsx
export function StopPanel({ data, onClose, onSelectTrain }: Props) {
  if (!data) {
    return null;
  }
  // On masque les passages déjà partis (le panneau peut vieillir entre deux rafraîchissements)
  // et les directions qui n'ont plus aucun passage à venir.
  const now = Date.now();
  const directions = data.directions
    .map((dir) => ({
      ...dir,
      passages: dir.passages.filter((p) => new Date(p.expectedTime).getTime() > now),
    }))
    .filter((dir) => dir.passages.length > 0);
  return (
    <div
      style={{
        position: "absolute",
        top: 12,
        right: 12,
        width: 260,
        padding: 16,
        background: "#fff",
        borderRadius: 8,
        boxShadow: "0 2px 12px rgba(0,0,0,.2)",
        font: "14px sans-serif",
      }}
    >
      <button
        onClick={onClose}
        style={{ float: "right", border: "none", background: "none", cursor: "pointer", fontSize: 20, lineHeight: 1, padding: 4 }}
        aria-label="Fermer"
      >
        ✕
      </button>
      <h3 style={{ margin: "0 0 8px" }}>{data.stationName}</h3>
      {directions.length === 0 && (
        <p style={{ margin: "4px 0", color: "#666" }}>Aucun passage annoncé.</p>
      )}
      {directions.map((dir) => (
        <div key={dir.destination} style={{ margin: "8px 0 0" }}>
          <p style={{ margin: "0 0 2px", fontWeight: 600 }}>→ {dir.destination}</p>
          <ul style={{ margin: "0 0 0 16px", padding: 0, listStyle: "none" }}>
            {dir.passages.map((p, i) => (
              <li key={i}>
                <button
                  onClick={() => onSelectTrain?.(p.journeyRef)}
                  style={{
                    border: "none", background: "none", padding: "2px 0", cursor: "pointer",
                    font: "inherit", color: "#1d4ed8", textAlign: "left", width: "100%",
                  }}
                  title="Suivre ce métro"
                >
                  {formatEta(p.expectedTime)}
                </button>
              </li>
            ))}
          </ul>
        </div>
      ))}
    </div>
  );
}
```

- [ ] **Step 2: `App` — id de station suivi + rafraîchissement**

Dans `App.tsx`, importer `VEHICLE_POLL_MS` :

```tsx
import { LINE_ID, VEHICLE_POLL_MS } from "./api/config";
```

Ajouter l'état (après `station`) :

```tsx
  const [selectedStationId, setSelectedStationId] = useState<string | null>(null);
```

Dans `onStationClick`, mémoriser l'id sélectionné (juste avant le `try`) :

```tsx
      setSelectedStationId(id);
```

Dans `closeStation`, arrêter le suivi de station :

```tsx
  const closeStation = () => {
    setStation(null);
    setSelectedStationId(null);
    map?.setFilter("stops-selected", ["==", ["get", "id"], "__none__"]);
  };
```

Dans `onClick` (clic véhicule), ajouter l'oubli de la station suivie à côté de l'effacement existant :

```tsx
      setStation(null);
      setSelectedStationId(null);
      map.setFilter("stops-selected", ["==", ["get", "id"], "__none__"]);
```

Ajouter un effet de rafraîchissement (après l'effet de clic) :

```tsx
  // Le panneau passages est rafraîchi au rythme du poll tant qu'une station est sélectionnée,
  // pour que les ETA vivent et que les passages partis disparaissent (sinon on affiche des
  // « imminent » fantômes figés au fetch initial).
  useEffect(() => {
    if (!selectedStationId) {
      return;
    }
    let cancelled = false;
    const timer = window.setInterval(async () => {
      try {
        const fresh = await fetchDepartures(LINE_ID, selectedStationId);
        if (!cancelled) {
          setStation(fresh);
        }
      } catch {
        // on conserve l'affichage courant
      }
    }, VEHICLE_POLL_MS);
    return () => {
      cancelled = true;
      window.clearInterval(timer);
    };
  }, [selectedStationId]);
```

- [ ] **Step 3: Build**

Run: `cd frontend && npm run build`
Expected: build OK.

- [ ] **Step 4: Contrôle visuel (utilisateur)**

Ouvrir une station : les passages affichés sont réels (plus de triple « imminent » fantôme) ; les ETA décroissent et se rafraîchissent ; une station sans passage à venir (fin de service) affiche « Aucun passage annoncé ».

- [ ] **Step 5: Commit**

```bash
git add frontend/src/ui/StopPanel.tsx frontend/src/App.tsx
git commit -m "fix(front): panneau passages rafraîchi + masque les passages déjà partis

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

## Task 13: Front — taille des véhicules variable selon le zoom (#3)

**Files:**
- Modify: `frontend/src/map/VehicleLayer.ts`

**Interfaces:**
- Produces: la couche symbol `vehicles` a un `icon-size` interpolé sur le zoom (petit de loin, plus gros en zoom rapproché).

- [ ] **Step 1: `icon-size` interpolé sur le zoom**

Dans `VehicleLayer.ts`, couche symbol `vehicles`, remplacer `"icon-size": 0.8` par une interpolation :

```typescript
          "icon-size": ["interpolate", ["linear"], ["zoom"], 10, 0.5, 13, 0.85, 16, 1.5],
```

- [ ] **Step 2: Build**

Run: `cd frontend && npm run build`
Expected: build OK.

- [ ] **Step 3: Contrôle visuel (utilisateur)**

Les flèches grossissent en zoomant et rapetissent en dézoomant, de façon fluide.

- [ ] **Step 4: Commit**

```bash
git add frontend/src/map/VehicleLayer.ts
git commit -m "feat(front): taille des véhicules interpolée selon le zoom

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```
