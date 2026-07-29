# Extension multi-ligne — métro complet : plan d'implémentation

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Étendre MapIDF du MVP mono-ligne (métro 9) aux 16 lignes de métro, avec un modèle N-lignes générique où chaque ligne porte N branches.

**Architecture:** Un `LineRegistry` en mémoire devient la source unique de vérité (lignes, branches, géométries indexées, horaires projetés, stations) ; il est publié par échange atomique après chaque chargement GTFS et réhydratable depuis PostGIS. La base sort du chemin de requête : `/network`, `/vehicles` et `/stations/{id}/departures` sont servis depuis le registry et le snapshot temps réel. Le loader GTFS ne conserve que les parcours représentatifs des branches retenues par couverture gloutonne (915 `stop_time` au lieu de 941 959).

**Tech Stack:** Spring Boot 4.1, Java 25, Lombok, Jackson 3 (`tools.jackson`), Hibernate Spatial + JTS 1.20, PostGIS, Flyway, Testcontainers, React + MapLibre GL, Vite.

**Spec:** [docs/superpowers/specs/2026-07-29-multi-ligne-metro-design.md](../specs/2026-07-29-multi-ligne-metro-design.md) — toutes les valeurs chiffrées y sont mesurées, pas estimées.

## Global Constraints

- **Jackson 3** (`tools.jackson.databind`, `tools.jackson.core`) — jamais `com.fasterxml`. Sur un `JsonNode`, utiliser **`.asString()`**, pas `.asText()`.
- Noms d'API Jackson 3 vérifiés sur les sources 3.1.1 : `JsonToken.PROPERTY_NAME` (et non `FIELD_NAME`), `JsonParser.currentName()`, `JsonParser.currentToken()`, `ObjectMapper.createParser(InputStream)`, `ObjectMapper.readTree(JsonParser)`. **`JacksonException extends RuntimeException`** en 3.x — aucune exception vérifiée à déclarer.
- **TDD strict** : le test qui échoue avant l'implémentation, à chaque tâche.
- Records pour les DTO immuables. Lombok `@Getter/@Builder/@NoArgsConstructor/@AllArgsConstructor` pour les entités JPA, en suivant exactement le style de `Route`/`Stop` existants.
- Vérification de référence : `./mvnw verify` depuis `backend/` (inclut les IT Testcontainers). Front : `npm run build` depuis `frontend/`.
- **Ne jamais démarrer ni arrêter le backend, le front ou Docker** — l'utilisateur les gère depuis son IDE. Demander si un lancement est nécessaire.
- `PRIM_API_KEY` vit dans `.env`, gitignoré. Ne jamais le commiter, ne jamais l'inscrire dans un test.
- **Décision produit ferme : aucun seuil d'ETA ne doit masquer ou atténuer un train.** Le signal de fiabilité est structurel (nombre d'appels SIRI), jamais temporel.
- Identifiant public d'une ligne = `route_short_name` normalisé en minuscules (`9`, `3b`, `7b`).
- Dérivation du LineRef SIRI : `IDFM:C01379` → `STIF:Line::C01379:` (vérifiée sur les 16 lignes).
- Commits fréquents, un par tâche minimum, en français, format Conventional Commits (`feat(back):`, `refactor(front):`…).

## Note de séquencement — état intermédiaire assumé

**Entre la tâche 4 et la tâche 12, l'API HTTP des lignes n'existe pas.** La tâche 4 supprime `LineController`, `NetworkQueryService`, `ScheduleProvider` et leurs IT ; les tâches 10 à 12 reconstruisent la nouvelle surface. C'est volontaire : une bascule de schéma ne se fait pas à moitié, et un dual-write transitoire ajouterait du code à supprimer ensuite.

Pendant cet intervalle, `./mvnw verify` doit rester **vert** (il reste `SmokeIT`, `SchemaIT`, les IT du loader et les tests unitaires). Le front reste cassé fonctionnellement jusqu'à la tâche 13, mais `npm run build` continue de passer jusqu'à la tâche 13 incluse — il ne fait aucun appel réseau.

## File Structure

### Backend — créés

| Fichier | Responsabilité |
|---|---|
| `configurations/properties/NetworkProperties.java` | Config `app.network` : modes suivis, exclusions |
| `data/enums/TransportMode.java` | Mode de transport ↔ `route_type` GTFS |
| `gtfs/LineDescriptor.java` | Une ligne telle que décrite par `routes.txt` : id public, LineRef dérivé, couleur normalisée |
| `gtfs/BranchSelector.java` | Couverture gloutonne : quels tracés retenir par (route, sens) |
| `data/entity/Branch.java` | Une branche persistée : tracé, sens, terminus |
| `data/repositories/BranchRepository.java` | Lecture des branches avec leur route (`JOIN FETCH`) |
| `network/LineBranch.java` | Branche en mémoire : géométrie indexée, arrêts projetés, index O(1) |
| `network/TrackedLine.java` | Ligne suivie en mémoire, avec ses branches |
| `network/Station.java` | Station dédupliquée : quais membres, lignes desservantes |
| `network/NetworkSnapshot.java` | État réseau immuable + index de résolution |
| `network/NetworkRegistryBuilder.java` | Construit un `NetworkSnapshot` depuis PostGIS (2 requêtes) |
| `network/LineRegistry.java` | Publie et résout l'état réseau (échange atomique) |
| `controllers/network/NetworkController.java` | `GET /network` |
| `controllers/network/NetworkResponse.java` | Payload de `/network` |
| `controllers/vehicles/VehiclesController.java` | `GET /vehicles` |
| `controllers/stations/StationsController.java` | `GET /stations/{id}/departures` |
| `src/test/resources/application-test.yml` | Neutralise les tâches planifiées en test |

### Backend — modifiés

| Fichier | Changement |
|---|---|
| `data/entity/Route.java` | `+mode`, `+siriLineRef`, `-geom` |
| `data/entity/StopTime.java` | `trip` → `branch` |
| `data/repositories/StopTimeRepository.java` | Requête de réhydratation par branche |
| `data/repositories/StopRepository.java` | `+findByGtfsIdIn` |
| `gtfs/GtfsStaticLoader.java` | Découverte par mode, deux passes, branches, stations parentes |
| `gtfs/GtfsStaticService.java` | Publie le registry au lieu de cacher une géométrie |
| `rt/RealtimePoller.java` | gzip, parse streaming, filtrage registry, `recordedAt` |
| `rt/RtSnapshot.java` | `LiveJourney.recordedAt` |
| `position/PositionEngine.java` | Choix de branche, confiance, `journeyRef`/`lineId` |
| `position/Vehicle.java` | `+lineId`, `+recordedAt`, `+confidence`, `tripId` → `journeyRef` |
| `services/StationDepartureService.java` | Regroupement par ligne puis direction |
| `src/main/resources/application.yml` | `app.line` → `app.network`, compression HTTP |

### Backend — supprimés

`data/entity/Trip.java`, `data/repositories/TripRepository.java`, `configurations/properties/LineProperties.java`, `position/ScheduleProvider.java`, `position/LineSchedule.java`, `position/DirectionSchedule.java`, `services/NetworkQueryService.java`, `controllers/lines/` (tout le paquet), et les IT `LineControllerShapeIT`, `LineControllerVehiclesIT`, `LineControllerDeparturesIT`, `NetworkQueryServiceIT`, `ScheduleProviderTest`.

### Frontend — créés / modifiés / supprimés

| Fichier | Changement |
|---|---|
| `api/network.ts` | **Créé** — `fetchNetwork`, `fetchVehicles`, `fetchDepartures` |
| `api/types.ts` | Types `NetworkResponse`, `VehiclesResponse`, `DeparturesResponse` refaits |
| `map/useNetwork.ts` | **Créé** — remplace `useLineShape` : couches `line-shapes` + `stops` |
| `map/VehicleLayer.ts` | Icône par couleur, opacité selon la confiance, boucle allégée |
| `map/useVehicles.ts` | Poll unique réseau, `Map<journeyRef, V>` pour les panneaux |
| `ui/LinePicker.tsx` | **Créé** — liste des lignes avec bascule et compteur |
| `ui/StopPanel.tsx` | Groupé par ligne, badge de retard |
| `ui/VehiclePanel.tsx` | Position approximative, fraîcheur de la donnée |
| `App.tsx` | Orchestration réseau, filtre de lignes |
| `api/config.ts` | **Supprime** `LINE_ID` |
| `map/useLineShape.ts` | **Supprimé** |
| `ui/Legend.tsx` | **Supprimé** (remplacé par `LinePicker`) |

---

## Task 1: Tests déterministes et configuration réseau

> **Correction apportée pendant l'exécution.** Ce préambule affirmait que la suite de tests téléchargeait le GTFS IDFM réel et interrogeait PRIM. C'était **faux** : un `application-test.yml` existait déjà dans `backend/src/main/resources/` et neutralisait les deux (`gtfs-static-url` et `realtime-base-url` vides). Il n'avait pas été vu parce que seul `src/test/resources/` avait été inspecté à la rédaction. Le défaut réel est ailleurs, et la tâche le corrige quand même : cette configuration de test vivait dans `main/resources`, donc **embarquée dans le jar de production**, où un `--spring.profiles.active=test` aurait silencieusement coupé le chargement GTFS et le poller.

`@EnableScheduling` est actif et `GtfsStaticService.refresh()` a `initialDelay = 0` : sans neutralisation, chaque IT déclencherait le téléchargement du GTFS et un appel PRIM, échecs avalés par les `try/catch`. Cette configuration doit donc rester — mais **au bon endroit**, d'autant que le registry devient un état global qu'un refresh de fond pourrait écraser en pleine IT.

**Files:**
- Create: `backend/src/test/resources/application-test.yml`
- Create: `backend/src/main/java/com/mapidf/data/enums/TransportMode.java`
- Create: `backend/src/main/java/com/mapidf/configurations/properties/NetworkProperties.java`
- Create: `backend/src/test/java/com/mapidf/data/enums/TransportModeTest.java`
- Modify: `backend/src/main/resources/application.yml`

**Interfaces:**
- Produces: `TransportMode` (enum `TRAM, METRO, RAIL, BUS, CABLE, FUNICULAR`) avec `int gtfsRouteType()` et `static Optional<TransportMode> fromRouteType(int)`.
- Produces: `NetworkProperties` record avec `List<TransportMode> modes()` et `List<String> exclude()`.

- [ ] **Step 1: Écrire le test qui échoue**

`backend/src/test/java/com/mapidf/data/enums/TransportModeTest.java` :

```java
package com.mapidf.data.enums;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class TransportModeTest {

    @Test
    void mapsGtfsRouteTypesMeasuredOnTheIdfmFeed() {
        // Valeurs relevées le 2026-07-29 sur routes.txt IDFM : 0=tram(17), 1=métro(16),
        // 2=rail(24), 3=bus(1410), 6=câble(1), 7=funiculaire(1).
        assertThat(TransportMode.fromRouteType(1)).contains(TransportMode.METRO);
        assertThat(TransportMode.fromRouteType(0)).contains(TransportMode.TRAM);
        assertThat(TransportMode.fromRouteType(2)).contains(TransportMode.RAIL);
        assertThat(TransportMode.fromRouteType(3)).contains(TransportMode.BUS);
        assertThat(TransportMode.fromRouteType(6)).contains(TransportMode.CABLE);
        assertThat(TransportMode.fromRouteType(7)).contains(TransportMode.FUNICULAR);
    }

    @Test
    void returnsEmptyForAnUnknownRouteType() {
        assertThat(TransportMode.fromRouteType(99)).isEqualTo(Optional.empty());
    }

    @Test
    void exposesTheGtfsRouteTypeOfEachMode() {
        assertThat(TransportMode.METRO.getGtfsRouteType()).isEqualTo(1);
    }
}
```

- [ ] **Step 2: Lancer le test pour vérifier qu'il échoue**

Run: `cd backend && ./mvnw test -Dtest=TransportModeTest`
Expected: FAIL — `cannot find symbol: class TransportMode`

- [ ] **Step 3: Implémenter `TransportMode`**

`backend/src/main/java/com/mapidf/data/enums/TransportMode.java` — même style que `ErrorCode` :

```java
package com.mapidf.data.enums;

import java.util.Arrays;
import java.util.Optional;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Mode de transport ↔ {@code route_type} GTFS. Le périmètre suivi est piloté par
 * {@code app.network.modes} : passer du métro au tram est un changement de configuration.
 */
@Getter
@RequiredArgsConstructor
public enum TransportMode {
    TRAM(0),
    METRO(1),
    RAIL(2),
    BUS(3),
    CABLE(6),
    FUNICULAR(7);

    private final int gtfsRouteType;

    public static Optional<TransportMode> fromRouteType(int routeType) {
        return Arrays.stream(values())
            .filter(mode -> mode.gtfsRouteType == routeType)
            .findFirst();
    }
}
```

- [ ] **Step 4: Lancer le test pour vérifier qu'il passe**

Run: `cd backend && ./mvnw test -Dtest=TransportModeTest`
Expected: PASS (3 tests)

- [ ] **Step 5: Créer `NetworkProperties`**

`backend/src/main/java/com/mapidf/configurations/properties/NetworkProperties.java` :

```java
package com.mapidf.configurations.properties;

import java.util.List;

import com.mapidf.data.enums.TransportMode;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Périmètre du réseau suivi. {@code modes} sélectionne les {@code route_type} GTFS à charger,
 * {@code exclude} permet d'écarter une {@code route_id} précise (ligne au référentiel atypique).
 */
@ConfigurationProperties(prefix = "app.network")
public record NetworkProperties(
    List<TransportMode> modes,
    List<String> exclude
) {
    public NetworkProperties {
        modes = modes == null ? List.of() : List.copyOf(modes);
        exclude = exclude == null ? List.of() : List.copyOf(exclude);
    }

    public boolean tracks(TransportMode mode) {
        return modes.contains(mode);
    }

    public boolean isExcluded(String gtfsRouteId) {
        return exclude.contains(gtfsRouteId);
    }
}
```

- [ ] **Step 6: Remplacer `app.line` par `app.network` dans `application.yml`**

Dans `backend/src/main/resources/application.yml`, supprimer entièrement le bloc `app.line` et le remplacer par :

```yaml
app:
  network:
    # route_type GTFS suivis. Mesuré le 2026-07-29 : route_type=1 donne exactement les
    # 16 lignes de métro, une par ligne commerciale. Passer au tram = ajouter TRAM.
    modes: [METRO]
    # route_id à écarter (ligne dont la dérivation du LineRef SIRI échouerait).
    exclude: []
```

Ajouter également, sous `server:` (la réponse `/vehicles` fait ~140 Ko pour 705 véhicules et descend sous 20 Ko compressée) :

```yaml
server:
  compression:
    enabled: true
    mime-types: application/json
    min-response-size: 4096
```

Ne pas supprimer `LineProperties.java` maintenant : `LineController`, `GtfsStaticService` et `RealtimePoller` s'en servent encore. Sa suppression est portée par la tâche 4.

- [ ] **Step 7: Neutraliser les tâches planifiées en test**

`backend/src/test/resources/application-test.yml` :

```yaml
# Le profil "test" (cf. @MapIdfTest) doit être hermétique. Sans ce fichier, @EnableScheduling
# déclenche GtfsStaticService.refresh() avec initialDelay=0, qui télécharge les 109 Mo du GTFS
# IDFM réel, et RealtimePoller.poll(), qui appelle PRIM — les deux échouant silencieusement
# dans leur try/catch. Un refresh de fond écraserait en plus le LineRegistry en pleine IT.
# Les deux méthodes sortent immédiatement quand leur URL est vide.
app:
  prim:
    gtfs-static-url: ""
    realtime-base-url: ""
    api-key: "test-key-not-a-secret"
  network:
    modes: [METRO]
    exclude: []
```

- [ ] **Step 8: Vérifier que la suite complète reste verte et n'appelle plus le réseau**

Run: `cd backend && ./mvnw verify`
Expected: BUILD SUCCESS. Dans les logs, **aucune** ligne `[GTFS] Réseau ligne ... rechargé` ni `[RT] Poll réussi` ni `[RT] Échec du poll` : les deux tâches planifiées sortent avant tout appel.

- [ ] **Step 9: Commit**

```bash
cd /home/abodet/workspace/perso/MapIDF
git add backend/src/main/java/com/mapidf/data/enums/TransportMode.java \
        backend/src/main/java/com/mapidf/configurations/properties/NetworkProperties.java \
        backend/src/test/java/com/mapidf/data/enums/TransportModeTest.java \
        backend/src/test/resources/application-test.yml \
        backend/src/main/resources/application.yml
git commit -m "feat(back): configuration réseau par mode et tests hermétiques

app.network.modes remplace app.line : le périmètre suivi est désormais un
ensemble de route_type GTFS, pas une ligne unique. TransportMode porte la
correspondance mesurée sur routes.txt IDFM (route_type=1 = les 16 lignes
de métro).

Ajoute application-test.yml : la suite téléchargeait jusqu'ici le GTFS
IDFM réel (109 Mo) et interrogeait PRIM à chaque IT, les échecs étant
avalés par les try/catch. Devient bloquant avec un LineRegistry global
qu'un refresh de fond peut écraser en pleine IT.

Active aussi la compression HTTP : /vehicles fera ~140 Ko pour
705 véhicules, sous 20 Ko compressé."
```

---

## Task 2: `LineDescriptor` — dérivation du référentiel de ligne

Traduit une ligne de `routes.txt` en ligne suivie : identifiant public, LineRef SIRI dérivé, couleur normalisée en CSS. Classe pure, aucune I/O.

**Files:**
- Create: `backend/src/main/java/com/mapidf/gtfs/LineDescriptor.java`
- Create: `backend/src/test/java/com/mapidf/gtfs/LineDescriptorTest.java`

**Interfaces:**
- Consumes: `TransportMode` (tâche 1).
- Produces: `LineDescriptor` record — `String id, String gtfsRouteId, String siriLineRef, String shortName, String color, TransportMode mode` ; fabrique `static LineDescriptor of(String gtfsRouteId, String shortName, String color, TransportMode mode)`.

- [ ] **Step 1: Écrire le test qui échoue**

`backend/src/test/java/com/mapidf/gtfs/LineDescriptorTest.java` :

```java
package com.mapidf.gtfs;

import com.mapidf.data.enums.TransportMode;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class LineDescriptorTest {

    @Test
    void derivesTheSiriLineRefFromTheGtfsRouteId() {
        // Dérivation vérifiée le 2026-07-29 sur les 16 lignes de métro : toutes présentes
        // dans le flux estimated-timetable avec ce LineRef.
        LineDescriptor nine = LineDescriptor.of("IDFM:C01379", "9", "D2D200", TransportMode.METRO);
        assertThat(nine.siriLineRef()).isEqualTo("STIF:Line::C01379:");
    }

    @Test
    void keepsTheLastSegmentOfTheRouteIdAsTheSiriCode() {
        // Un route_id à segments supplémentaires ne doit pas casser la dérivation.
        LineDescriptor line = LineDescriptor.of("IDFM:Line:C01377", "7", "FF82B4", TransportMode.METRO);
        assertThat(line.siriLineRef()).isEqualTo("STIF:Line::C01377:");
    }

    @Test
    void normalisesThePublicIdToLowercaseWithoutSpaces() {
        // Le GTFS écrit "3B" et "7B" ; les URL publiques doivent être /lines/3b et /lines/7b.
        assertThat(LineDescriptor.of("IDFM:C01386", "3B", "82C8E6", TransportMode.METRO).id())
            .isEqualTo("3b");
        assertThat(LineDescriptor.of("IDFM:C01387", " 7B ", "82DC73", TransportMode.METRO).id())
            .isEqualTo("7b");
        assertThat(LineDescriptor.of("IDFM:C01384", "14", "640082", TransportMode.METRO).id())
            .isEqualTo("14");
    }

    @Test
    void normalisesTheColorToCss() {
        // route_color GTFS est un hex SANS '#' ; MapLibre rejette la couche sans le '#'.
        assertThat(LineDescriptor.of("IDFM:C01379", "9", "D2D200", TransportMode.METRO).color())
            .isEqualTo("#D2D200");
        assertThat(LineDescriptor.of("IDFM:C01379", "9", "#D2D200", TransportMode.METRO).color())
            .isEqualTo("#D2D200");
    }

    @Test
    void fallsBackToBlackWhenTheColorIsMissing() {
        assertThat(LineDescriptor.of("IDFM:C01379", "9", null, TransportMode.METRO).color())
            .isEqualTo("#000000");
        assertThat(LineDescriptor.of("IDFM:C01379", "9", "  ", TransportMode.METRO).color())
            .isEqualTo("#000000");
    }

    @Test
    void keepsTheShortNameUntouchedForDisplay() {
        assertThat(LineDescriptor.of("IDFM:C01386", "3B", "82C8E6", TransportMode.METRO).shortName())
            .isEqualTo("3B");
    }
}
```

- [ ] **Step 2: Lancer le test pour vérifier qu'il échoue**

Run: `cd backend && ./mvnw test -Dtest=LineDescriptorTest`
Expected: FAIL — `cannot find symbol: class LineDescriptor`

- [ ] **Step 3: Implémenter `LineDescriptor`**

`backend/src/main/java/com/mapidf/gtfs/LineDescriptor.java` :

```java
package com.mapidf.gtfs;

import com.mapidf.data.enums.TransportMode;

/**
 * Une ligne suivie, telle que décrite par {@code routes.txt}. Tout est dérivé du GTFS :
 * aucune saisie manuelle par ligne (vérifié le 2026-07-29 sur les 16 lignes de métro).
 *
 * @param id           identifiant public d'URL : {@code route_short_name} en minuscules ("9", "3b")
 * @param gtfsRouteId  {@code route_id} GTFS ("IDFM:C01379")
 * @param siriLineRef  LineRef du flux temps réel, dérivé ("STIF:Line::C01379:")
 * @param shortName    nom court d'affichage, non normalisé ("3B")
 * @param color        couleur CSS, préfixée '#'
 */
public record LineDescriptor(String id, String gtfsRouteId, String siriLineRef,
                             String shortName, String color, TransportMode mode) {

    public static LineDescriptor of(String gtfsRouteId, String shortName, String color, TransportMode mode) {
        return new LineDescriptor(
            publicId(shortName),
            gtfsRouteId,
            siriLineRef(gtfsRouteId),
            shortName == null ? "" : shortName.trim(),
            cssColor(color),
            mode);
    }

    private static String publicId(String shortName) {
        return shortName == null ? "" : shortName.trim().toLowerCase().replace(" ", "");
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
```

- [ ] **Step 4: Lancer le test pour vérifier qu'il passe**

Run: `cd backend && ./mvnw test -Dtest=LineDescriptorTest`
Expected: PASS (6 tests)

- [ ] **Step 5: Commit**

```bash
cd /home/abodet/workspace/perso/MapIDF
git add backend/src/main/java/com/mapidf/gtfs/LineDescriptor.java \
        backend/src/test/java/com/mapidf/gtfs/LineDescriptorTest.java
git commit -m "feat(back): LineDescriptor, référentiel de ligne dérivé du GTFS

Traduit une ligne de routes.txt en ligne suivie : identifiant public
(route_short_name en minuscules, donc 3b et 7b), LineRef SIRI dérivé du
dernier segment du route_id, couleur normalisée en CSS.

La dérivation route_id -> STIF:Line::<code>: a été vérifiée sur les
16 lignes de métro le 2026-07-29 : toutes présentes dans le flux
estimated-timetable avec ce LineRef."
```

---

## Task 3: `BranchSelector` — couverture gloutonne des tracés

Une route référence plusieurs `shape_id` (mesuré : 112 candidats sur le métro, jusqu'à 10 pour un seul sens de la 14). Le loader actuel garde **le tracé le plus long**, ce qui casse la ligne 7 : 8 arrêts jusqu'à 1547 m du tracé retenu, la branche Ivry se projetant n'importe où.

On retient à la place l'ensemble minimal de tracés couvrant **tous** les arrêts de la (route, sens) : 37 tracés sur tout le métro, 100 % de couverture, et un critère testable au lieu d'un critère arbitraire.

**Files:**
- Create: `backend/src/main/java/com/mapidf/gtfs/BranchSelector.java`
- Create: `backend/src/test/java/com/mapidf/gtfs/BranchSelectorTest.java`

**Interfaces:**
- Produces: `BranchSelector.Candidate` record — `String shapeId, String tripId, List<String> stopIds`.
- Produces: `static List<Candidate> BranchSelector.select(List<Candidate> candidates)` — sous-ensemble couvrant, ordre déterministe.

- [ ] **Step 1: Écrire le test qui échoue**

`backend/src/test/java/com/mapidf/gtfs/BranchSelectorTest.java` :

```java
package com.mapidf.gtfs;

import java.util.List;

import com.mapidf.gtfs.BranchSelector.Candidate;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class BranchSelectorTest {

    @Test
    void keepsASingleCandidateWhenThereIsOnlyOne() {
        List<Candidate> selected = BranchSelector.select(List.of(
            new Candidate("SH9", "T9", List.of("S1", "S2", "S3"))));

        assertThat(selected).extracting(Candidate::shapeId).containsExactly("SH9");
    }

    @Test
    void dropsACandidateWhoseStopsAreAlreadyCovered() {
        // Cas majoritaire mesuré : 13 des 16 lignes de métro n'ont qu'un tracé retenu par sens,
        // les autres shapes étant des services partiels inclus dans le plus long.
        List<Candidate> selected = BranchSelector.select(List.of(
            new Candidate("SH_FULL", "T_FULL", List.of("S1", "S2", "S3", "S4")),
            new Candidate("SH_PARTIAL", "T_PARTIAL", List.of("S1", "S2"))));

        assertThat(selected).extracting(Candidate::shapeId).containsExactly("SH_FULL");
    }

    @Test
    void keepsASecondCandidateWhenItAddsAtLeastOneStop() {
        // Cas de la ligne 7 : deux branches partageant leur tronc, chacune avec ses arrêts propres.
        List<Candidate> selected = BranchSelector.select(List.of(
            new Candidate("SH_VILLEJUIF", "TA", List.of("P1", "P2", "P3", "P4")),
            new Candidate("SH_IVRY", "TB", List.of("P1", "P2", "P3", "P5"))));

        // L'intention est « les deux branches sont retenues » : l'ordre relatif est incidental
        // ici (à taille égale, le départage par shapeId placerait SH_IVRY en premier), et c'est
        // isDeterministicWhenTwoCandidatesHaveTheSameSize qui couvre l'ordre.
        assertThat(selected).extracting(Candidate::shapeId)
            .containsExactlyInAnyOrder("SH_VILLEJUIF", "SH_IVRY");
        assertThat(selected).flatExtracting(Candidate::stopIds)
            .contains("P4", "P5");
    }

    @Test
    void coversEveryStopOfTheGroup() {
        List<Candidate> candidates = List.of(
            new Candidate("SH_A", "TA", List.of("A", "B", "C")),
            new Candidate("SH_B", "TB", List.of("A", "B", "D")),
            new Candidate("SH_C", "TC", List.of("A", "E")));

        List<Candidate> selected = BranchSelector.select(candidates);

        assertThat(selected).flatExtracting(Candidate::stopIds)
            .containsAll(List.of("A", "B", "C", "D", "E"));
    }

    @Test
    void prefersTheLongestCandidateFirst() {
        List<Candidate> selected = BranchSelector.select(List.of(
            new Candidate("SH_SHORT", "TS", List.of("S1", "S2")),
            new Candidate("SH_LONG", "TL", List.of("S1", "S2", "S3", "S4"))));

        assertThat(selected).extracting(Candidate::shapeId).containsExactly("SH_LONG");
    }

    @Test
    void isDeterministicWhenTwoCandidatesHaveTheSameSize() {
        // Sans départage stable, deux exécutions retiendraient un ordre différent et les
        // assertions des IT deviendraient intermittentes. On départage par shapeId.
        List<Candidate> first = BranchSelector.select(List.of(
            new Candidate("SH_B", "TB", List.of("X", "Y")),
            new Candidate("SH_A", "TA", List.of("X", "Z"))));
        List<Candidate> second = BranchSelector.select(List.of(
            new Candidate("SH_A", "TA", List.of("X", "Z")),
            new Candidate("SH_B", "TB", List.of("X", "Y"))));

        assertThat(first).extracting(Candidate::shapeId).containsExactly("SH_A", "SH_B");
        assertThat(second).extracting(Candidate::shapeId).containsExactly("SH_A", "SH_B");
    }

    @Test
    void returnsAnEmptyListWhenThereIsNoCandidate() {
        assertThat(BranchSelector.select(List.of())).isEmpty();
    }
}
```

- [ ] **Step 2: Lancer le test pour vérifier qu'il échoue**

Run: `cd backend && ./mvnw test -Dtest=BranchSelectorTest`
Expected: FAIL — `cannot find symbol: class BranchSelector`

- [ ] **Step 3: Implémenter `BranchSelector`**

`backend/src/main/java/com/mapidf/gtfs/BranchSelector.java` :

```java
package com.mapidf.gtfs;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Choisit les tracés à conserver pour un couple (route, sens) : l'ensemble minimal couvrant
 * TOUS les arrêts desservis, par glouton.
 *
 * <p>Remplace le critère « garder le tracé le plus long », qui casse les lignes à branches :
 * mesuré le 2026-07-29, la ligne 7 avait 8 arrêts jusqu'à 1547 m du tracé retenu, la branche
 * Ivry se projetant n'importe où sur la branche Villejuif. Le critère « couvrir tous les
 * arrêts » est lui vérifiable — d'où les tests d'intégration qui l'affirment.
 *
 * <p>Sur tout le métro : 112 candidats → 37 retenus (un seul par sens pour 13 des 16 lignes ;
 * deux pour la 7 et la 13, deux dans un sens pour la 10).
 *
 * <p>Coût en O(candidats²) par groupe, soit au pire une centaine de comparaisons d'ensembles
 * (10 candidats maximum sur le métro) : négligeable, et le restera sur le RER.
 */
public final class BranchSelector {

    private BranchSelector() {
    }

    /**
     * Un tracé candidat = son {@code shape_id}, la course la plus longue qui l'emprunte,
     * et les arrêts de cette course dans l'ordre de desserte.
     */
    public record Candidate(String shapeId, String tripId, List<String> stopIds) {
        public Candidate {
            stopIds = List.copyOf(stopIds);
        }
    }

    public static List<Candidate> select(List<Candidate> candidates) {
        // Le plus desservant d'abord ; départage par shapeId pour que la sélection soit
        // reproductible (sinon les assertions des IT deviennent intermittentes).
        List<Candidate> ordered = candidates.stream()
            .sorted(Comparator.comparingInt((Candidate c) -> c.stopIds().size()).reversed()
                .thenComparing(Candidate::shapeId))
            .toList();

        Set<String> universe = new HashSet<>();
        ordered.forEach(candidate -> universe.addAll(candidate.stopIds()));

        List<Candidate> selected = new ArrayList<>();
        Set<String> covered = new HashSet<>();
        for (Candidate candidate : ordered) {
            if (covered.containsAll(candidate.stopIds())) {
                continue; // service partiel : n'apporte aucun arrêt nouveau
            }
            selected.add(candidate);
            covered.addAll(candidate.stopIds());
            if (covered.equals(universe)) {
                break;
            }
        }
        return List.copyOf(selected);
    }
}
```

- [ ] **Step 4: Lancer le test pour vérifier qu'il passe**

Run: `cd backend && ./mvnw test -Dtest=BranchSelectorTest`
Expected: PASS (7 tests)

- [ ] **Step 5: Vérifier la suite complète**

Run: `cd backend && ./mvnw verify`
Expected: BUILD SUCCESS

- [ ] **Step 6: Commit**

```bash
cd /home/abodet/workspace/perso/MapIDF
git add backend/src/main/java/com/mapidf/gtfs/BranchSelector.java \
        backend/src/test/java/com/mapidf/gtfs/BranchSelectorTest.java
git commit -m "feat(back): couverture gloutonne des tracés d'une ligne

Retient l'ensemble minimal de tracés couvrant tous les arrêts d'un couple
(route, sens), au lieu du seul tracé le plus long.

Mesuré le 2026-07-29 : le critère actuel casse la ligne 7 (8 arrêts
jusqu'à 1547 m du tracé retenu, la branche Ivry se projetant sur la
branche Villejuif). Le glouton donne 37 tracés pour tout le métro au lieu
de 112 candidats, avec 100 % des arrêts couverts — un critère vérifiable
là où « le plus long » ne permet d'affirmer rien d'utile.

Départage par shapeId à taille égale pour que la sélection soit
reproductible."
```

---

## Task 4: Bascule du modèle — branches en base, suppression de `trip`

Le modèle passe de « une route porte une géométrie » à « une route porte N branches ». Comme seuls les parcours représentatifs sont conservés, `trip` n'est plus qu'une jonction 1:1 avec la branche et son `headsign` n'est jamais affiché : elle disparaît, `stop_time` s'accrochant directement à la branche.

**Cette tâche supprime l'API HTTP des lignes.** Les tâches 10 à 12 la reconstruisent. C'est délibéré : une bascule de schéma ne se fait pas à moitié. `./mvnw verify` doit rester vert (il reste `SmokeIT`, `SchemaIT`, les IT du loader, les tests unitaires).

Le loader est ici porté **mécaniquement** sur `Branch` en gardant sa logique actuelle (un seul tracé, le plus long, et un `routeId` en paramètre). La découverte par mode et le glouton arrivent en tâche 5.

**Files:**
- Create: `backend/src/main/resources/db/migration/V4__branches.sql`
- Create: `backend/src/main/java/com/mapidf/data/entity/Branch.java`
- Create: `backend/src/main/java/com/mapidf/data/repositories/BranchRepository.java`
- Modify: `backend/src/main/java/com/mapidf/data/entity/Route.java`
- Modify: `backend/src/main/java/com/mapidf/data/entity/StopTime.java`
- Modify: `backend/src/main/java/com/mapidf/data/repositories/StopTimeRepository.java`
- Modify: `backend/src/main/java/com/mapidf/gtfs/GtfsStaticLoader.java`
- Modify: `backend/src/main/java/com/mapidf/gtfs/GtfsStaticService.java`
- Modify: `backend/src/test/java/com/mapidf/data/SchemaIT.java`
- Modify: `backend/src/test/java/com/mapidf/gtfs/GtfsStaticLoaderIT.java`
- Delete: `backend/src/main/java/com/mapidf/data/entity/Trip.java`
- Delete: `backend/src/main/java/com/mapidf/data/repositories/TripRepository.java`
- Delete: `backend/src/main/java/com/mapidf/position/ScheduleProvider.java`
- Delete: `backend/src/main/java/com/mapidf/position/LineSchedule.java`
- Delete: `backend/src/main/java/com/mapidf/position/DirectionSchedule.java`
- Delete: `backend/src/main/java/com/mapidf/services/NetworkQueryService.java`
- Delete: `backend/src/main/java/com/mapidf/controllers/lines/` (tout le paquet)
- Delete: `backend/src/main/java/com/mapidf/configurations/properties/LineProperties.java`
- Delete: `backend/src/test/java/com/mapidf/position/ScheduleProviderTest.java`
- Delete: `backend/src/test/java/com/mapidf/services/NetworkQueryServiceIT.java`
- Delete: `backend/src/test/java/com/mapidf/controllers/lines/` (les trois IT)

**Interfaces:**
- Produces: entité `Branch` — `UUID id, Route route, String gtfsShapeId, String representativeTrip, Short direction, String terminusName, LineString geom`, avec `@Getter/@Builder/@NoArgsConstructor/@AllArgsConstructor`.
- Produces: `BranchRepository.findAllWithRoute()` → `List<Branch>` avec la route déjà chargée.
- Produces: `StopTimeRepository.findAllForRegistry()` → `List<StopTime>` avec branche et arrêt déjà chargés, triés par branche puis `stopSequence`.
- Produces: `GtfsStaticLoader.loadFromZip(InputStream, String routeId)` — signature inchangée à cette étape.
- Produces: `Route.getMode()` (String), `Route.getSiriLineRef()` ; `Route.getGeom()` **n'existe plus**.
- Produces: `StopTime.getBranch()` remplace `StopTime.getTrip()`.

- [ ] **Step 1: Écrire la migration**

`backend/src/main/resources/db/migration/V4__branches.sql` :

```sql
-- Une ligne porte désormais N branches (mesuré : 37 tracés sur le métro, dont 2 pour la 7,
-- 2 pour la 13, 2 dans un sens de la 10). Le tracé unique par route rendait la ligne 7
-- fausse de 1547 m. La géométrie migre donc de route vers branch.
--
-- Migration destructrice : les données sont intégralement régénérées au premier refresh
-- GTFS, déclenché au démarrage (initialDelay = 0). Conséquence assumée : une fenêtre de 404
-- entre la migration et la fin de ce premier chargement.

CREATE TABLE branch (
    id                  UUID NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    route_id            UUID NOT NULL REFERENCES route(id),
    gtfs_shape_id       TEXT NOT NULL,
    representative_trip TEXT NOT NULL,
    direction           SMALLINT NOT NULL,
    terminus_name       TEXT,
    geom                geometry(LineString, 4326) NOT NULL,
    UNIQUE (route_id, gtfs_shape_id, direction)
);

-- Le registry se réhydrate depuis la base au démarrage : un redémarrage ne doit pas imposer
-- de retélécharger 109 Mo de GTFS. Il lui faut donc le mode et le LineRef en base.
ALTER TABLE route ADD COLUMN mode TEXT;
ALTER TABLE route ADD COLUMN siri_line_ref TEXT;
ALTER TABLE route DROP COLUMN geom;

-- stop_time s'accroche à la branche, plus à la course.
DELETE FROM stop_time;
ALTER TABLE stop_time DROP CONSTRAINT stop_time_trip_id_stop_sequence_key;
ALTER TABLE stop_time DROP COLUMN trip_id;
ALTER TABLE stop_time ADD COLUMN branch_id UUID NOT NULL REFERENCES branch(id);
ALTER TABLE stop_time ADD CONSTRAINT stop_time_branch_id_stop_sequence_key
    UNIQUE (branch_id, stop_sequence);

DROP TABLE trip;

-- Index d'hygiène de clé étrangère. À ces volumes (37 branches, 915 stop_times) PostgreSQL
-- fait un seq scan et c'est plus rapide qu'un parcours d'index : ils ne sont pas là pour la
-- performance. Aucun index spatial GiST n'est nécessaire — aucune requête spatiale n'est
-- faite, la projection des arrêts sur le tracé s'exécute en Java au build du registry.
CREATE INDEX idx_branch_route ON branch (route_id);
CREATE INDEX idx_stop_time_branch ON stop_time (branch_id);
```

- [ ] **Step 2: Écrire les tests de schéma qui échouent**

Remplacer `backend/src/test/java/com/mapidf/data/SchemaIT.java` par :

```java
package com.mapidf.data;

import java.util.List;

import com.mapidf.MapIdfTest;
import com.mapidf.data.repositories.BranchRepository;
import com.mapidf.data.repositories.RouteRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.assertThat;

@MapIdfTest
class SchemaIT {

    @Autowired
    RouteRepository routeRepository;

    @Autowired
    BranchRepository branchRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void schemaValidatesAndRepositoryWorks() {
        // ddl-auto: validate — ce test échoue si le mapping JPA diverge du schéma Flyway.
        assertThat(routeRepository.findByGtfsId("UNKNOWN")).isEmpty();
        assertThat(branchRepository.findAllWithRoute()).isEmpty();
    }

    @Test
    void createsBranchTableAndDropsTrip() {
        List<String> tables = jdbcTemplate.queryForList(
            "SELECT tablename FROM pg_tables WHERE schemaname = 'public'", String.class);
        assertThat(tables).contains("branch");
        assertThat(tables).doesNotContain("trip");
    }

    @Test
    void movesGeometryFromRouteToBranch() {
        List<String> routeColumns = jdbcTemplate.queryForList(
            "SELECT column_name FROM information_schema.columns WHERE table_name = 'route'",
            String.class);
        assertThat(routeColumns).contains("mode", "siri_line_ref").doesNotContain("geom");

        List<String> branchColumns = jdbcTemplate.queryForList(
            "SELECT column_name FROM information_schema.columns WHERE table_name = 'branch'",
            String.class);
        assertThat(branchColumns).contains("geom", "direction", "terminus_name", "gtfs_shape_id");
    }

    @Test
    void repointsStopTimeToBranch() {
        List<String> columns = jdbcTemplate.queryForList(
            "SELECT column_name FROM information_schema.columns WHERE table_name = 'stop_time'",
            String.class);
        assertThat(columns).contains("branch_id").doesNotContain("trip_id");
    }

    @Test
    void keepsTheIndexes() {
        List<String> indexes = jdbcTemplate.queryForList(
            "SELECT indexname FROM pg_indexes WHERE schemaname = 'public'", String.class);
        assertThat(indexes).contains(
            "idx_stop_parent_station", "idx_stop_time_stop",
            "idx_branch_route", "idx_stop_time_branch");
    }
}
```

- [ ] **Step 3: Lancer le test pour vérifier qu'il échoue**

Run: `cd backend && ./mvnw verify -Dit.test=SchemaIT`
Expected: FAIL — `cannot find symbol: class BranchRepository`

- [ ] **Step 4: Créer l'entité `Branch` et son repository**

`backend/src/main/java/com/mapidf/data/entity/Branch.java` — même style que `Route` :

```java
package com.mapidf.data.entity;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.locationtech.jts.geom.LineString;

/**
 * Une branche d'une ligne : un tracé, un sens, son terminus. Une ligne simple en a une par
 * sens ; la 7 et la 13 en ont deux par sens, la 10 deux dans un sens.
 */
@Getter
@ToString
@Entity
@Table(name = "branch")
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode
public class Branch {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", columnDefinition = "uuid")
    private UUID id;

    @ManyToOne
    @JoinColumn(name = "route_id")
    @ToString.Exclude
    private Route route;

    @Column(name = "gtfs_shape_id")
    private String gtfsShapeId;

    /** {@code trip_id} GTFS de la course représentative retenue — traçabilité seulement. */
    @Column(name = "representative_trip")
    private String representativeTrip;

    @Column(name = "direction")
    private Short direction;

    @Column(name = "terminus_name")
    private String terminusName;

    @Column(name = "geom", columnDefinition = "geometry(LineString,4326)")
    private LineString geom;
}
```

`backend/src/main/java/com/mapidf/data/repositories/BranchRepository.java` :

```java
package com.mapidf.data.repositories;

import java.util.List;
import java.util.UUID;

import com.mapidf.data.entity.Branch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface BranchRepository extends JpaRepository<Branch, UUID> {

    /**
     * JOIN FETCH explicite : sans lui, la réhydratation du registry ferait un N+1
     * (une requête par branche pour charger sa route).
     */
    @Query("""
        SELECT b FROM Branch b
        JOIN FETCH b.route r
        ORDER BY r.gtfsId, b.direction, b.gtfsShapeId
        """)
    List<Branch> findAllWithRoute();
}
```

- [ ] **Step 5: Adapter `Route` et `StopTime`**

Dans `Route.java`, supprimer le champ `geom` (et l'import `LineString`), puis ajouter :

```java
    @Column(name = "mode")
    private String mode;

    @Column(name = "siri_line_ref")
    private String siriLineRef;
```

Dans `StopTime.java`, remplacer le champ `trip` par :

```java
    @ManyToOne
    @JoinColumn(name = "branch_id")
    @ToString.Exclude
    private Branch branch;
```

- [ ] **Step 6: Adapter `StopTimeRepository`**

Remplacer le contenu de `StopTimeRepository.java` par :

```java
package com.mapidf.data.repositories;

import java.util.List;
import java.util.UUID;

import com.mapidf.data.entity.StopTime;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface StopTimeRepository extends JpaRepository<StopTime, UUID> {

    /**
     * Tout ce qu'il faut au registry, en UNE requête : les arrêts de chaque branche, dans
     * l'ordre de desserte, avec branche et arrêt déjà chargés. Le volume est petit par
     * construction (915 lignes sur tout le métro) puisque seuls les parcours représentatifs
     * sont persistés.
     */
    @Query("""
        SELECT st FROM StopTime st
        JOIN FETCH st.branch b
        JOIN FETCH st.stop s
        ORDER BY b.id, st.stopSequence
        """)
    List<StopTime> findAllForRegistry();

    /** Arrêts d'une branche donnée, pour les assertions d'intégration. */
    @Query("""
        SELECT st FROM StopTime st
        JOIN FETCH st.branch b
        JOIN FETCH st.stop s
        WHERE b.gtfsShapeId = :shapeId
        ORDER BY st.stopSequence
        """)
    List<StopTime> findByShapeId(String shapeId);
}
```

- [ ] **Step 7: Supprimer les classes devenues mortes**

```bash
cd /home/abodet/workspace/perso/MapIDF/backend/src
rm main/java/com/mapidf/data/entity/Trip.java
rm main/java/com/mapidf/data/repositories/TripRepository.java
rm main/java/com/mapidf/position/ScheduleProvider.java
rm main/java/com/mapidf/position/LineSchedule.java
rm main/java/com/mapidf/position/DirectionSchedule.java
rm main/java/com/mapidf/services/NetworkQueryService.java
rm main/java/com/mapidf/configurations/properties/LineProperties.java
rm -r main/java/com/mapidf/controllers/lines
rm test/java/com/mapidf/position/ScheduleProviderTest.java
rm test/java/com/mapidf/services/NetworkQueryServiceIT.java
rm -r test/java/com/mapidf/controllers/lines
```

`PositionEngine` et `Vehicle` restent en place mais ne compileront plus (`LineSchedule` supprimé). Les neutraliser temporairement : dans `PositionEngine.java`, supprimer les méthodes `computeAll`, `compute`, `vehicleAt` et `pickDirection`, en **ne gardant que** `stopKey`, `indexOfStop`, `bearing`, `clamp` et le champ `DIGIT_GROUP`. La tâche 9 réécrit le moteur sur les branches. `PositionEngineTest` doit être réduit aux seuls tests de `stopKey` (supprimer les autres méthodes de test) ; la tâche 9 le reconstruit.

- [ ] **Step 8: Porter le loader sur `Branch`**

Dans `GtfsStaticLoader.java`, remplacer `TripRepository` par `BranchRepository` dans les champs, et :

- `loadFromZipFile` : ordre de purge `stopTimeRepository` → `branchRepository` → `stopRepository` → `routeRepository` ;
- `Route.builder()` : supprimer `.geom(...)`, ajouter `.mode(TransportMode.METRO.name())` et `.siriLineRef(LineDescriptor.of(routeId, routeInfo.shortName(), routeInfo.color(), TransportMode.METRO).siriLineRef())`, et `.color(...)` reçoit la couleur normalisée via `LineDescriptor` ;
- remplacer `persistTrips` par une méthode qui crée **une branche par sens**, portant le tracé le plus long (logique actuelle conservée) :

```java
    /**
     * Port mécanique sur Branch de la logique existante : un tracé unique (le plus long) et
     * une branche par sens. La tâche 5 remplace cette sélection par la couverture gloutonne.
     */
    private Map<String, Branch> persistBranches(Route route, List<TripRow> tripRows, LineString shape) {
        Map<Short, TripRow> longestByDirection = new HashMap<>();
        for (TripRow row : tripRows) {
            longestByDirection.putIfAbsent(row.direction(), row);
        }
        Map<String, Branch> branchesByTripId = new HashMap<>();
        for (Map.Entry<Short, TripRow> entry : longestByDirection.entrySet()) {
            Branch branch = branchRepository.save(Branch.builder()
                .route(route)
                .gtfsShapeId(route.getGtfsId() + ":" + entry.getKey())
                .representativeTrip(entry.getValue().tripId())
                .direction(entry.getKey())
                .terminusName(entry.getValue().headsign())
                .geom(shape)
                .build());
            branchesByTripId.put(entry.getValue().tripId(), branch);
        }
        return branchesByTripId;
    }
```

Adapter ensuite `parseStopTimes` et `persistStopTimes` pour filtrer sur `branchesByTripId.containsKey(tripId)` et poser `.branch(branchesByTripId.get(row.tripId()))` au lieu de `.trip(...)`.

- [ ] **Step 9: Adapter `GtfsStaticService`**

Supprimer le champ `LineProperties line`, le champ `volatile LineString routeGeometry`, les méthodes `cacheGeometry()` et `getRouteGeometry()`, et le champ `ScheduleProvider`. La méthode `refresh()` devient :

```java
    @Scheduled(initialDelay = 0, fixedRateString = "P1D")
    public void refresh() {
        if (prim.gtfsStaticUrl() == null || prim.gtfsStaticUrl().isBlank()) {
            log.info("[GTFS] URL statique non configurée, refresh ignoré");
            return;
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(prim.gtfsStaticUrl()))
                .header(prim.authHeader(), prim.apiKey())
                .GET()
                .build();
            HttpResponse<java.io.InputStream> response =
                httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            loader.loadFromZip(response.body(), "IDFM:C01379");
            log.info("[GTFS] Réseau rechargé");
        } catch (Exception e) {
            log.error("[GTFS] Échec du refresh statique", e);
        }
    }
```

La `route_id` en dur ici est un provisoire assumé : la tâche 5 la remplace par `NetworkProperties`.

- [ ] **Step 10: Adapter les IT du loader**

Dans `GtfsStaticLoaderIT.java` : remplacer `TripRepository tripRepository` par `BranchRepository branchRepository`, et les assertions sur `route.getGeom()` par des assertions sur la branche. Par exemple, `loadsLineIntoDb` devient :

```java
    @Test
    void loadsLineIntoDb() throws Exception {
        try (var in = getClass().getResourceAsStream("/gtfs-mini.zip")) {
            loader.loadFromZip(in, "TEST9");
        }
        Route route = routeRepository.findByGtfsId("TEST9").orElseThrow();
        assertThat(route.getSiriLineRef()).isEqualTo("STIF:Line::TEST9:");
        assertThat(branchRepository.findAllWithRoute()).singleElement()
            .satisfies(branch -> assertThat(branch.getGeom().getNumPoints()).isEqualTo(3));
        assertThat(stopRepository.count()).isEqualTo(3);
        assertThat(stopTimeRepository.findAllForRegistry()).hasSize(3);
    }
```

Adapter de même `loadsOnlyTheRequestedRouteFromAMultiRouteFeed` (remplacer `tripRepository.count()` par `branchRepository.count()`) et `usesTheLongestShapeWhenRouteHasSeveralVariants` (l'assertion porte sur `branch.getGeom().getNumPoints()`). `readsParentStationFromStopsAndLeavesItNullWhenAbsent` est inchangé.

- [ ] **Step 11: Lancer la suite complète**

Run: `cd backend && ./mvnw verify`
Expected: BUILD SUCCESS. Il ne reste plus d'IT de contrôleur — c'est attendu jusqu'à la tâche 10.

- [ ] **Step 12: Commit**

```bash
cd /home/abodet/workspace/perso/MapIDF
git add -A backend/src
git commit -m "refactor(back)!: une ligne porte N branches, suppression de trip

La géométrie migre de route vers une nouvelle table branch (tracé, sens,
terminus) : un tracé unique par ligne rendait la ligne 7 fausse de 1547 m.
route gagne mode et siri_line_ref pour que le registry soit réhydratable
depuis la base, sans retélécharger 109 Mo de GTFS à chaque redémarrage.

trip devient une jonction 1:1 avec la branche dont le headsign n'est
jamais affiché : stop_time s'accroche directement à branch et trip est
supprimée.

Supprime aussi ScheduleProvider, LineSchedule, DirectionSchedule,
NetworkQueryService, LineController et LineProperties. L'API HTTP des
lignes est volontairement absente jusqu'aux tâches 10 à 12, qui
reconstruisent /network, /vehicles et /stations/{id}/departures : une
bascule de schéma ne se fait pas à moitié, et un dual-write transitoire
n'ajouterait que du code à supprimer ensuite.

BREAKING CHANGE: /lines/{id}/shape, /lines/{id}/vehicles et
/lines/{id}/stations/{sid}/departures n'existent plus."
```

---

## Task 5: Loader — découverte par mode, deux passes, branches, stations parentes

Le loader **accumule aujourd'hui toutes les lignes de `stop_times.txt` en mémoire** avant de persister. Sur la ligne 9 ça passe ; sur 16 lignes ce sont **941 959** entités dans une seule transaction — l'OOM que le streaming du zip devait éviter revient par la porte de derrière. Or seuls les parcours représentatifs sont exploités, et `calendar.txt` n'étant pas chargé la table est de toute façon incapable de répondre à un horaire théorique daté : **915 lignes suffisent**.

**Files:**
- Modify: `backend/src/main/java/com/mapidf/gtfs/GtfsStaticLoader.java`
- Modify: `backend/src/main/java/com/mapidf/gtfs/GtfsStaticService.java`
- Modify: `backend/src/main/java/com/mapidf/data/repositories/StopRepository.java`
- Modify: `backend/src/test/java/com/mapidf/gtfs/GtfsStaticLoaderIT.java`
- Create: `backend/src/test/resources/gtfs-branch.zip` (généré au step 1)

**Interfaces:**
- Consumes: `NetworkProperties` (tâche 1), `LineDescriptor` (tâche 2), `BranchSelector` (tâche 3), `Branch`/`BranchRepository` (tâche 4).
- Produces: `GtfsStaticLoader.load(InputStream zip)` — **remplace** `loadFromZip(InputStream, String)`. Le périmètre vient de `NetworkProperties`, plus d'un paramètre.
- Produces: `StopRepository.findByGtfsIdIn(Collection<String>)` → `List<Stop>`.

- [ ] **Step 1: Créer la fixture GTFS à branche**

La fixture doit couvrir en un seul zip : deux modes (dont un à exclure), un tracé partiel à écarter, une vraie branche à conserver, deux sens, et une correspondance entre deux lignes.

```bash
cd /tmp && rm -rf gtfs-branch && mkdir gtfs-branch && cd gtfs-branch

cat > routes.txt <<'CSV'
route_id,route_short_name,route_color,route_type
IDFM:C01379,9,D2D200,1
IDFM:C01377,7,FF82B4,1
IDFM:C09999,999,000000,3
CSV

cat > trips.txt <<'CSV'
route_id,trip_id,trip_headsign,direction_id,shape_id
IDFM:C01379,T9,Gamma,0,SH9
IDFM:C01379,T9S,Beta,0,SH9S
IDFM:C01379,T9R,Alpha,1,SH9R
IDFM:C01377,T7A,Villejuif,0,SH7A
IDFM:C01377,T7B,Ivry,0,SH7B
IDFM:C09999,TB1,Bus,0,SHB
CSV

cat > stop_times.txt <<'CSV'
trip_id,stop_id,stop_sequence,arrival_time,departure_time
T9,S1,1,08:00:00,08:00:00
T9,S2,2,08:05:00,08:05:00
T9,S3,3,08:10:00,08:10:00
T9S,S1,1,08:30:00,08:30:00
T9S,S2,2,08:35:00,08:35:00
T9R,S3,1,09:00:00,09:00:00
T9R,S2,2,09:05:00,09:05:00
T9R,S1,3,09:10:00,09:10:00
T7A,P1,1,07:00:00,07:00:00
T7A,P2,2,07:04:00,07:04:00
T7A,P3,3,07:08:00,07:08:00
T7A,P4,4,07:12:00,07:12:00
T7B,P1,1,07:20:00,07:20:00
T7B,P2,2,07:24:00,07:24:00
T7B,P3,3,07:28:00,07:28:00
T7B,P5,4,07:32:00,07:32:00
TB1,B1,1,06:00:00,06:00:00
TB1,B2,2,06:10:00,06:10:00
CSV

# S2 (ligne 9) et P2 (ligne 7) partagent le parent STC : correspondance à deux lignes.
cat > stops.txt <<'CSV'
stop_id,stop_name,stop_lat,stop_lon,location_type,parent_station
S1,Alpha,48.850,2.300,0,ST1
S2,Correspondance,48.850,2.310,0,STC
S3,Gamma,48.850,2.320,0,ST3
P1,Nord,48.870,2.310,0,PT1
P2,Correspondance,48.850,2.310,0,STC
P3,Sud,48.840,2.310,0,PT3
P4,Villejuif,48.830,2.300,0,PT4
P5,Ivry,48.830,2.320,0,PT5
B1,Arret bus 1,48.900,2.400,0,
B2,Arret bus 2,48.910,2.410,0,
ST1,Alpha,48.850,2.300,1,
STC,Correspondance,48.850,2.310,1,
ST3,Gamma,48.850,2.320,1,
PT1,Nord,48.870,2.310,1,
PT3,Sud,48.840,2.310,1,
PT4,Villejuif,48.830,2.300,1,
PT5,Ivry,48.830,2.320,1,
CSV

# SH7A et SH7B partagent leur tronc (P1->P2->P3) et divergent ensuite : P4 est à ~1,5 km
# de SH7B, exactement la pathologie mesurée sur la vraie ligne 7.
cat > shapes.txt <<'CSV'
shape_id,shape_pt_lat,shape_pt_lon,shape_pt_sequence
SH9,48.850,2.300,1
SH9,48.850,2.310,2
SH9,48.850,2.320,3
SH9S,48.850,2.300,1
SH9S,48.850,2.310,2
SH9R,48.850,2.320,1
SH9R,48.850,2.310,2
SH9R,48.850,2.300,3
SH7A,48.870,2.310,1
SH7A,48.850,2.310,2
SH7A,48.840,2.310,3
SH7A,48.830,2.300,4
SH7B,48.870,2.310,1
SH7B,48.850,2.310,2
SH7B,48.840,2.310,3
SH7B,48.830,2.320,4
SHB,48.900,2.400,1
SHB,48.910,2.410,2
CSV

zip -X -q /home/abodet/workspace/perso/MapIDF/backend/src/test/resources/gtfs-branch.zip \
    routes.txt trips.txt stop_times.txt stops.txt shapes.txt
unzip -l /home/abodet/workspace/perso/MapIDF/backend/src/test/resources/gtfs-branch.zip
```

Attendu : 5 entrées listées.

- [ ] **Step 2: Écrire l'IT qui échoue**

Les quatre fixtures existantes n'ont pas de colonne `route_type` : sans elle, `discoverLines` ne découvre aucune route. Les régénérer d'abord, en ajoutant la colonne à `routes.txt` et en laissant le reste intact :

**Attention à `gtfs-multi.zip`** : sa seconde route `TESTX` servait à vérifier qu'une seule route était chargée. Avec la découverte par mode, ce n'est plus un `route_id` non demandé mais une route hors périmètre — on lui donne donc `route_type=3` (bus), ce qui conserve exactement l'intention du test sous la nouvelle sémantique. Les trois autres fixtures n'ont qu'une route, en `route_type=1`.

```bash
cd /home/abodet/workspace/perso/MapIDF/backend/src/test/resources
for fixture in gtfs-mini gtfs-twoshapes gtfs-parent; do
  rm -rf /tmp/$fixture && mkdir -p /tmp/$fixture
  unzip -q -o $fixture.zip -d /tmp/$fixture
  awk 'NR==1 {print $0",route_type"} NR>1 {print $0",1"}' /tmp/$fixture/routes.txt > /tmp/$fixture/routes.new
  mv /tmp/$fixture/routes.new /tmp/$fixture/routes.txt
  (cd /tmp/$fixture && zip -X -q -r "$OLDPWD/$fixture.zip" .)
  echo "--- $fixture"; unzip -p $fixture.zip routes.txt
done

# gtfs-multi : TEST9 en métro, TESTX en bus (hors périmètre)
rm -rf /tmp/gtfs-multi && mkdir -p /tmp/gtfs-multi
unzip -q -o gtfs-multi.zip -d /tmp/gtfs-multi
cat > /tmp/gtfs-multi/routes.txt <<'CSV'
route_id,route_short_name,route_color,route_type
TEST9,9,D5C900,1
TESTX,X,112233,3
CSV
(cd /tmp/gtfs-multi && zip -X -q -r "$OLDPWD/gtfs-multi.zip" .)
unzip -p gtfs-multi.zip routes.txt
```

Attendu : `gtfs-mini`, `gtfs-twoshapes` et `gtfs-parent` ont un en-tête terminé par `route_type` et des lignes terminées par `,1` ; `gtfs-multi` liste `TEST9` en `1` et `TESTX` en `3`.

Ajouter ensuite à `backend/src/test/java/com/mapidf/gtfs/GtfsStaticLoaderIT.java` — en remplaçant les appels `loader.loadFromZip(in, "...")` par `loader.load(in)`, et en renommant `loadsOnlyTheRequestedRouteFromAMultiRouteFeed` en `loadsOnlyTheRoutesOfTheTrackedModes` (ses assertions restent valables : `TESTX` est désormais écartée parce qu'elle est en `route_type=3`) :

```java
    @Test
    void keepsOneBranchPerCoveringShapeAndDropsPartialServices() throws Exception {
        try (var in = getClass().getResourceAsStream("/gtfs-branch.zip")) {
            loader.load(in);
        }

        // Ligne 9 : SH9 (3 arrêts) couvre S1,S2,S3 ; SH9S (S1,S2) est un service partiel
        // inclus, donc écarté. Sens 1 : SH9R. => 2 branches.
        // Ligne 7 : SH7A (P1..P4) et SH7B (P1,P2,P3,P5) apportent chacune un arrêt propre.
        // => 2 branches. Total 4.
        assertThat(branchRepository.findAllWithRoute()).hasSize(4);
        assertThat(branchRepository.findAllWithRoute()).extracting(Branch::getGtfsShapeId)
            .containsExactlyInAnyOrder("SH9", "SH9R", "SH7A", "SH7B");
    }

    @Test
    void ignoresRoutesOutsideTheTrackedModes() throws Exception {
        try (var in = getClass().getResourceAsStream("/gtfs-branch.zip")) {
            loader.load(in);
        }
        // route_type=3 (bus) n'est pas dans app.network.modes (METRO en profil test).
        assertThat(routeRepository.findByGtfsId("IDFM:C09999")).isEmpty();
        assertThat(stopRepository.findAll()).extracting(Stop::getGtfsId)
            .doesNotContain("B1", "B2");
    }

    @Test
    void persistsOnlyTheStopTimesOfTheRetainedBranches() throws Exception {
        try (var in = getClass().getResourceAsStream("/gtfs-branch.zip")) {
            loader.load(in);
        }
        // 3 (SH9) + 3 (SH9R) + 4 (SH7A) + 4 (SH7B) = 14. Les 2 lignes de T9S et les 2 du bus
        // ne sont pas matérialisées : c'est ce qui fait passer le métro réel de 941 959 à 915.
        assertThat(stopTimeRepository.findAllForRegistry()).hasSize(14);
    }

    @Test
    void persistsParentStationsAsTheirOwnStops() throws Exception {
        try (var in = getClass().getResourceAsStream("/gtfs-branch.zip")) {
            loader.load(in);
        }
        // 8 quais métro (S1..S3, P1..P5) + 7 stations parentes = 15. Les parents portent leur
        // propre nom et leurs propres coordonnées : c'est ce qui rend le nom de station
        // déterministe sur une correspondance.
        assertThat(stopRepository.count()).isEqualTo(15);
        assertThat(stopRepository.findByGtfsId("STC")).isPresent()
            .get().extracting(Stop::getName).isEqualTo("Correspondance");
        assertThat(stopRepository.findByParentStation("STC")).extracting(Stop::getGtfsId)
            .containsExactlyInAnyOrder("S2", "P2");
    }

    @Test
    void derivesRouteMetadataFromTheFeed() throws Exception {
        try (var in = getClass().getResourceAsStream("/gtfs-branch.zip")) {
            loader.load(in);
        }
        Route seven = routeRepository.findByGtfsId("IDFM:C01377").orElseThrow();
        assertThat(seven.getShortName()).isEqualTo("7");
        assertThat(seven.getColor()).isEqualTo("#FF82B4");
        assertThat(seven.getSiriLineRef()).isEqualTo("STIF:Line::C01377:");
        assertThat(seven.getMode()).isEqualTo("METRO");
    }

    @Test
    void projectsBranchStopsOntoTheirOwnShape() throws Exception {
        try (var in = getClass().getResourceAsStream("/gtfs-branch.zip")) {
            loader.load(in);
        }
        // Chaque branche porte SES arrêts : P4 appartient à SH7A, P5 à SH7B. Avec un tracé
        // unique, l'un des deux se projetterait à ~1,5 km de sa position réelle.
        assertThat(stopTimeRepository.findByShapeId("SH7A"))
            .extracting(st -> st.getStop().getGtfsId())
            .containsExactly("P1", "P2", "P3", "P4");
        assertThat(stopTimeRepository.findByShapeId("SH7B"))
            .extracting(st -> st.getStop().getGtfsId())
            .containsExactly("P1", "P2", "P3", "P5");
    }
```

- [ ] **Step 3: Lancer l'IT pour vérifier qu'il échoue**

Run: `cd backend && ./mvnw verify -Dit.test=GtfsStaticLoaderIT`
Expected: FAIL — `cannot find symbol: method load(InputStream)`

- [ ] **Step 4: Ajouter `findByGtfsIdIn` à `StopRepository`**

```java
    List<Stop> findByGtfsIdIn(Collection<String> gtfsIds);
```

(avec l'import `java.util.Collection`.)

- [ ] **Step 5: Réécrire le loader**

Remplacer `loadFromZip(InputStream, String)` / `loadFromZipFile` par la séquence en deux passes. Injecter `NetworkProperties network` dans les champs. Le corps :

```java
    @Transactional
    public void load(InputStream zipIn) throws IOException {
        Path tempZip = Files.createTempFile("gtfs-static-", ".zip");
        try {
            Files.copy(zipIn, tempZip, StandardCopyOption.REPLACE_EXISTING);
            loadFromZipFile(tempZip);
        } finally {
            Files.deleteIfExists(tempZip);
        }
    }

    private void loadFromZipFile(Path zipPath) throws IOException {
        try (ZipFile zipFile = new ZipFile(zipPath.toFile())) {
            stopTimeRepository.deleteAllInBatch();
            branchRepository.deleteAllInBatch();
            stopRepository.deleteAllInBatch();
            routeRepository.deleteAllInBatch();

            // 1. Les lignes du périmètre, découvertes par mode.
            Map<String, LineDescriptor> lines = discoverLines(zipFile);
            if (lines.isEmpty()) {
                throw new IllegalStateException("aucune ligne pour les modes " + network.modes());
            }

            // 2. Les courses de ces lignes, indexées par (route, sens, tracé).
            List<TripRow> tripRows = parseTrips(zipFile, lines.keySet());

            // 3. PASSE 1 sur stop_times.txt : UNIQUEMENT le nombre d'arrêts par course.
            //    37 163 compteurs sur le métro réel, mémoire triviale — c'est cette passe qui
            //    remplace l'accumulation de 941 959 entités.
            Map<String, Integer> stopCounts = countStopsPerTrip(zipFile, tripRows);

            // 4. Meilleure course par (route, sens, tracé) : 112 candidates sur le métro réel.
            List<TripRow> candidates = bestTripPerShape(tripRows, stopCounts);

            // 5. PASSE 2 sur stop_times.txt : les lignes des seules 112 candidates. On a
            //    désormais leurs arrêts, donc de quoi faire tourner le glouton.
            Map<String, List<StopTimeRow>> rowsByTrip = parseStopTimesOfTrips(zipFile,
                candidates.stream().map(TripRow::tripId).collect(Collectors.toSet()));

            // 6. Couverture gloutonne par (route, sens) → 37 branches retenues sur le métro.
            List<RetainedBranch> retained = selectBranches(candidates, rowsByTrip);

            // 7. Les tracés des seules branches retenues.
            Map<String, LineString> shapes = loadShapes(zipFile,
                retained.stream().map(RetainedBranch::shapeId).collect(Collectors.toSet()));

            // 8. Les arrêts des branches retenues ET leurs stations parentes.
            Set<String> retainedTripIds = retained.stream()
                .map(RetainedBranch::tripId).collect(Collectors.toSet());
            rowsByTrip.keySet().retainAll(retainedTripIds); // 915 lignes au lieu de 941 959
            Set<String> stopIds = rowsByTrip.values().stream()
                .flatMap(List::stream).map(StopTimeRow::stopId).collect(Collectors.toSet());
            Map<String, Stop> stopsByGtfsId = persistStopsWithParents(zipFile, stopIds);

            persistRoutesBranchesAndStopTimes(lines, retained, shapes, rowsByTrip, stopsByGtfsId);
        }
    }

    /** routes.txt filtré sur app.network.modes, hors exclusions. */
    private Map<String, LineDescriptor> discoverLines(ZipFile zipFile) throws IOException {
        Map<String, LineDescriptor> lines = new LinkedHashMap<>();
        try (CSVParser parser = openCsv(zipFile, "routes.txt")) {
            for (CSVRecord r : parser) {
                String routeId = r.get("route_id");
                if (network.isExcluded(routeId)) {
                    continue;
                }
                Optional<TransportMode> mode = TransportMode
                    .fromRouteType(Integer.parseInt(r.get("route_type")));
                if (mode.isEmpty() || !network.tracks(mode.get())) {
                    continue;
                }
                lines.put(routeId, LineDescriptor.of(
                    routeId, r.get("route_short_name"), safe(r, "route_color"), mode.get()));
            }
        }
        log.info("[GTFS] {} ligne(s) découverte(s) pour les modes {}", lines.size(), network.modes());
        return lines;
    }

    private Map<String, Integer> countStopsPerTrip(ZipFile zipFile, List<TripRow> tripRows) throws IOException {
        Set<String> tripIds = tripRows.stream().map(TripRow::tripId).collect(Collectors.toSet());
        Map<String, Integer> counts = new HashMap<>();
        try (CSVParser parser = openCsv(zipFile, "stop_times.txt")) {
            for (CSVRecord r : parser) {
                String tripId = r.get("trip_id");
                if (tripIds.contains(tripId)) {
                    counts.merge(tripId, 1, Integer::sum);
                }
            }
        }
        return counts;
    }

    /**
     * La course la plus desservante par (route, sens, tracé) : 112 candidates sur le métro réel,
     * contre 37 163 courses au total. C'est sur ce sous-ensemble que la passe 2 travaille.
     */
    private List<TripRow> bestTripPerShape(List<TripRow> tripRows, Map<String, Integer> stopCounts) {
        Map<BranchKey, TripRow> bestByKey = new LinkedHashMap<>();
        for (TripRow row : tripRows) {
            BranchKey key = new BranchKey(row.routeId(), row.direction(), row.shapeId());
            TripRow current = bestByKey.get(key);
            if (current == null
                || stopCounts.getOrDefault(row.tripId(), 0) > stopCounts.getOrDefault(current.tripId(), 0)) {
                bestByKey.put(key, row);
            }
        }
        return List.copyOf(bestByKey.values());
    }

    /**
     * Couverture gloutonne par (route, sens), à partir des arrêts collectés en passe 2 pour les
     * seules candidates. Sur le métro réel : 112 candidates → 37 branches retenues, 100 % des
     * arrêts couverts.
     */
    private List<RetainedBranch> selectBranches(List<TripRow> candidates,
                                                Map<String, List<StopTimeRow>> rowsByTrip) {
        Map<DirectionKey, List<TripRow>> byDirection = candidates.stream()
            .collect(Collectors.groupingBy(
                row -> new DirectionKey(row.routeId(), row.direction()),
                LinkedHashMap::new, Collectors.toList()));

        List<RetainedBranch> retained = new ArrayList<>();
        byDirection.forEach((direction, rows) -> {
            Map<String, TripRow> byTripId = rows.stream()
                .collect(Collectors.toMap(TripRow::tripId, row -> row, (a, b) -> a, LinkedHashMap::new));
            List<BranchSelector.Candidate> selectorInput = rows.stream()
                .map(row -> new BranchSelector.Candidate(row.shapeId(), row.tripId(),
                    rowsByTrip.getOrDefault(row.tripId(), List.of()).stream()
                        .map(StopTimeRow::stopId).toList()))
                .toList();
            for (BranchSelector.Candidate kept : BranchSelector.select(selectorInput)) {
                TripRow row = byTripId.get(kept.tripId());
                retained.add(new RetainedBranch(direction.routeId(), direction.direction(),
                    kept.shapeId(), kept.tripId(), row.headsign()));
            }
        });
        log.info("[GTFS] {} candidate(s) → {} branche(s) retenue(s)", candidates.size(), retained.size());
        return retained;
    }

    /** PASSE 2 : les lignes des seules courses données, groupées et triées par stop_sequence. */
    private Map<String, List<StopTimeRow>> parseStopTimesOfTrips(ZipFile zipFile, Set<String> tripIds)
            throws IOException {
        Map<String, List<StopTimeRow>> byTrip = new LinkedHashMap<>();
        try (CSVParser parser = openCsv(zipFile, "stop_times.txt")) {
            for (CSVRecord r : parser) {
                String tripId = r.get("trip_id");
                if (!tripIds.contains(tripId)) {
                    continue;
                }
                byTrip.computeIfAbsent(tripId, key -> new ArrayList<>()).add(new StopTimeRow(
                    tripId, r.get("stop_id"), Integer.parseInt(r.get("stop_sequence")),
                    toSeconds(r.get("arrival_time")), toSeconds(r.get("departure_time"))));
            }
        }
        byTrip.values().forEach(rows -> rows.sort(Comparator.comparingInt(StopTimeRow::stopSequence)));
        return byTrip;
    }

    /** Les tracés des seules branches retenues (37 sur le métro, 8 110 points au total). */
    private Map<String, LineString> loadShapes(ZipFile zipFile, Set<String> shapeIds) throws IOException {
        Map<String, List<ShapePoint>> pointsByShape = new LinkedHashMap<>();
        try (CSVParser parser = openCsv(zipFile, "shapes.txt")) {
            for (CSVRecord r : parser) {
                String shapeId = r.get("shape_id");
                if (!shapeIds.contains(shapeId)) {
                    continue;
                }
                pointsByShape.computeIfAbsent(shapeId, key -> new ArrayList<>()).add(new ShapePoint(
                    Integer.parseInt(r.get("shape_pt_sequence")),
                    Double.parseDouble(r.get("shape_pt_lon")),
                    Double.parseDouble(r.get("shape_pt_lat"))));
            }
        }
        Map<String, LineString> shapes = new LinkedHashMap<>();
        pointsByShape.forEach((shapeId, points) -> {
            points.sort(Comparator.comparingInt(ShapePoint::sequence));
            shapes.put(shapeId, geometryFactory.createLineString(points.stream()
                .map(p -> new Coordinate(p.lon(), p.lat()))
                .toArray(Coordinate[]::new)));
        });
        if (shapes.size() < shapeIds.size()) {
            throw new IllegalStateException("tracé manquant pour " + (shapeIds.size() - shapes.size()) + " branche(s)");
        }
        return shapes;
    }

    private void persistRoutesBranchesAndStopTimes(Map<String, LineDescriptor> lines,
                                                    List<RetainedBranch> retained,
                                                    Map<String, LineString> shapes,
                                                    Map<String, List<StopTimeRow>> rowsByTrip,
                                                    Map<String, Stop> stopsByGtfsId) {
        Map<String, Route> routesByGtfsId = new LinkedHashMap<>();
        for (LineDescriptor descriptor : lines.values()) {
            routesByGtfsId.put(descriptor.gtfsRouteId(), routeRepository.save(Route.builder()
                .gtfsId(descriptor.gtfsRouteId())
                .shortName(descriptor.shortName())
                .color(descriptor.color())
                .mode(descriptor.mode().name())
                .siriLineRef(descriptor.siriLineRef())
                .build()));
        }

        List<StopTime> stopTimesToSave = new ArrayList<>();
        for (RetainedBranch item : retained) {
            List<StopTimeRow> rows = rowsByTrip.getOrDefault(item.tripId(), List.of());
            // Terminus = dernier arrêt du parcours : c'est lui qui départage deux branches d'un
            // même sens face au DestinationName du flux temps réel.
            String terminus = rows.isEmpty() ? item.headsign()
                : stopsByGtfsId.get(rows.getLast().stopId()).getName();
            Branch branch = branchRepository.save(Branch.builder()
                .route(routesByGtfsId.get(item.routeId()))
                .gtfsShapeId(item.shapeId())
                .representativeTrip(item.tripId())
                .direction(item.direction())
                .terminusName(terminus)
                .geom(shapes.get(item.shapeId()))
                .build());
            for (StopTimeRow row : rows) {
                stopTimesToSave.add(StopTime.builder()
                    .branch(branch)
                    .stop(stopsByGtfsId.get(row.stopId()))
                    .stopSequence(row.stopSequence())
                    .arrivalSec(row.arrivalSec())
                    .departureSec(row.departureSec())
                    .build());
            }
        }
        saveAllInBatches(stopTimeRepository, stopTimesToSave);
        log.info("[GTFS] {} route(s), {} branche(s), {} stop_time(s) persistés",
            routesByGtfsId.size(), retained.size(), stopTimesToSave.size());
    }
```

`parseTrips(ZipFile, Set<String> routeIds)` est la méthode existante, à adapter pour filtrer sur un **ensemble** de `route_id` au lieu d'un seul et pour renvoyer des `TripRow` portant aussi `routeId` et `shapeId`. `buildLongestShape` est **supprimée** (remplacée par `loadShapes`). `toSeconds`, `safe`, `openCsv` et `saveAllInBatches` sont conservés tels quels.

Records d'appui à ajouter en bas de la classe :

```java
    private record TripRow(String routeId, String tripId, String headsign, Short direction, String shapeId) {
    }

    private record BranchKey(String routeId, Short direction, String shapeId) {
    }

    private record DirectionKey(String routeId, Short direction) {
    }

    private record RetainedBranch(String routeId, Short direction, String shapeId,
                                  String tripId, String headsign) {
    }
```

`persistStopsWithParents` charge les quais **et** les parents en une lecture de `stops.txt` :

```java
    private Map<String, Stop> persistStopsWithParents(ZipFile zipFile, Set<String> stopIds) throws IOException {
        // Deux lectures logiques en une : on retient les quais demandés, on note leurs
        // parent_station, puis on retient aussi les lignes de ces parents (location_type=1).
        // Mesuré sur le métro : les 781 quais ont TOUS un parent, présent dans stops.txt.
        List<CSVRecord> all = new ArrayList<>();
        Set<String> parentIds = new HashSet<>();
        try (CSVParser parser = openCsv(zipFile, "stops.txt")) {
            for (CSVRecord r : parser) {
                String stopId = r.get("stop_id");
                if (stopIds.contains(stopId)) {
                    all.add(r);
                    String parent = safe(r, "parent_station");
                    if (parent != null) {
                        parentIds.add(parent);
                    }
                }
            }
        }
        try (CSVParser parser = openCsv(zipFile, "stops.txt")) {
            for (CSVRecord r : parser) {
                if (parentIds.contains(r.get("stop_id"))) {
                    all.add(r);
                }
            }
        }
        List<Stop> toSave = all.stream()
            .map(r -> Stop.builder()
                .gtfsId(r.get("stop_id"))
                .name(r.get("stop_name"))
                .parentStation(safe(r, "parent_station"))
                .geom(geometryFactory.createPoint(new Coordinate(
                    Double.parseDouble(r.get("stop_lon")), Double.parseDouble(r.get("stop_lat")))))
                .build())
            .toList();
        Map<String, Stop> byGtfsId = new HashMap<>();
        for (Stop stop : saveAllInBatches(stopRepository, toSave)) {
            byGtfsId.put(stop.getGtfsId(), stop);
        }
        return byGtfsId;
    }
```

Les records `StopTimeRow` et `ShapePoint` existent déjà dans la classe et sont conservés tels quels.

- [ ] **Step 6: Adapter `GtfsStaticService`**

Remplacer `loader.loadFromZip(response.body(), "IDFM:C01379")` par `loader.load(response.body())`.

- [ ] **Step 7: Lancer les IT du loader**

Run: `cd backend && ./mvnw verify -Dit.test=GtfsStaticLoaderIT`
Expected: PASS — 4 branches, 14 stop_times, 15 arrêts, bus ignoré, P4 sur SH7A et P5 sur SH7B.

- [ ] **Step 8: Vérifier la suite complète**

Run: `cd backend && ./mvnw verify`
Expected: BUILD SUCCESS

- [ ] **Step 9: Commit**

```bash
cd /home/abodet/workspace/perso/MapIDF
git add -A backend/src
git commit -m "feat(back): loader multi-lignes en deux passes, branches et stations parentes

Le périmètre vient désormais de app.network.modes : le loader découvre les
lignes dans routes.txt par route_type, dérive LineRef et couleur, et ne
persiste que les parcours représentatifs des branches retenues par
couverture gloutonne.

Motif : le loader accumulait toutes les lignes de stop_times.txt en
mémoire avant de persister. Sur 16 lignes ce sont 941 959 entités dans une
seule transaction, soit l'OOM que le streaming du zip devait éviter. Or
seuls les parcours représentatifs sont exploités, et calendar.txt n'étant
pas chargé la table ne peut de toute façon pas répondre à un horaire
théorique daté : 915 lignes suffisent, et le pic mémoire devient constant.

Persiste aussi les stations parentes comme arrêts à part entière : elles
portent leur propre nom et leurs propres coordonnées, ce qui rend le nom
de station déterministe sur une correspondance (il venait jusqu'ici du
premier quai rencontré) et améliore le placement sur la carte.

Ajoute la fixture gtfs-branch.zip : deux lignes métro dont une à branche,
une ligne bus à exclure, un service partiel à écarter, deux sens, et une
correspondance entre deux lignes."
```

---

## Task 6: Modèle réseau en mémoire et `LineRegistry`

Le registry devient la source unique de vérité et **sort la base du chemin de requête** : `/network`, `/vehicles` et `/stations/{id}/departures` ne feront aucune requête SQL. PostGIS n'est lu qu'au démarrage et après le refresh quotidien.

Il porte le `LengthIndexedLine` préconstruit (aujourd'hui rebâti à chaque appel de `/vehicles`) et une `Map<stopKey, index>` par branche. Sans cette map, le choix de branche coûterait ~100 000 comparaisons de chaînes par requête (705 courses × jusqu'à 4 branches candidates × ~35 arrêts).

**Files:**
- Create: `backend/src/main/java/com/mapidf/network/LineBranch.java`
- Create: `backend/src/main/java/com/mapidf/network/TrackedLine.java`
- Create: `backend/src/main/java/com/mapidf/network/Station.java`
- Create: `backend/src/main/java/com/mapidf/network/NetworkSnapshot.java`
- Create: `backend/src/main/java/com/mapidf/network/NetworkRegistryBuilder.java`
- Create: `backend/src/main/java/com/mapidf/network/LineRegistry.java`
- Create: `backend/src/test/java/com/mapidf/network/LineBranchTest.java`
- Create: `backend/src/test/java/com/mapidf/network/NetworkSnapshotTest.java`
- Create: `backend/src/test/java/com/mapidf/network/NetworkRegistryBuilderIT.java`

**Interfaces:**
- Consumes: `BranchRepository.findAllWithRoute()`, `StopTimeRepository.findAllForRegistry()`, `StopRepository.findByGtfsIdIn(...)` (tâches 4-5), `StopOnLine` et `PositionEngine.stopKey(String)` (existants).
- Produces: `LineBranch` record — `String shapeId, short direction, String terminusName, LineString geom, LengthIndexedLine indexed, List<StopOnLine> stops, Map<String,Integer> indexByStopKey` ; fabrique `static LineBranch of(String shapeId, short direction, String terminusName, LineString geom, List<StopOnLine> stops)` ; méthode `int indexOf(String stopKey)` (−1 si absent).
- Produces: `TrackedLine` record — `String id, String gtfsRouteId, String siriLineRef, String shortName, String color, String mode, List<LineBranch> branches`.
- Produces: `Station` record — `String id, String name, double lat, double lng, List<String> platformIds, List<String> lineIds`.
- Produces: `NetworkSnapshot` record — `List<TrackedLine> lines, Map<String,TrackedLine> linesById, Map<String,TrackedLine> linesBySiriRef, List<Station> stations, Map<String,Station> stationsById` ; fabriques `of(lines, stations)` et `empty()`.
- Produces: `NetworkRegistryBuilder.build()` → `NetworkSnapshot`.
- Produces: `LineRegistry.current()`, `publish(NetworkSnapshot)`, `requireLine(String id)`, `requireStation(String id)`, `trackedSiriLineRefs()` → `Set<String>`.

- [ ] **Step 1: Écrire les tests unitaires qui échouent**

`backend/src/test/java/com/mapidf/network/LineBranchTest.java` :

```java
package com.mapidf.network;

import java.util.List;

import com.mapidf.position.StopOnLine;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.PrecisionModel;
import static org.assertj.core.api.Assertions.assertThat;

class LineBranchTest {

    private static final GeometryFactory GF = new GeometryFactory(new PrecisionModel(), 4326);

    private static LineString line() {
        return GF.createLineString(new Coordinate[]{
            new Coordinate(2.300, 48.850), new Coordinate(2.320, 48.850)});
    }

    @Test
    void looksUpStopIndexInConstantTime() {
        LineBranch branch = LineBranch.of("SH9", (short) 0, "Gamma", line(), List.of(
            new StopOnLine("1", "Alpha", 0.0, 0),
            new StopOnLine("2", "Beta", 0.01, 300),
            new StopOnLine("3", "Gamma", 0.02, 600)));

        assertThat(branch.indexOf("1")).isZero();
        assertThat(branch.indexOf("2")).isEqualTo(1);
        assertThat(branch.indexOf("3")).isEqualTo(2);
    }

    @Test
    void returnsMinusOneForAStopOutsideTheBranch() {
        LineBranch branch = LineBranch.of("SH9", (short) 0, "Gamma", line(), List.of(
            new StopOnLine("1", "Alpha", 0.0, 0)));

        // Garantit qu'un train de branche non couverte est écarté proprement au lieu de lever,
        // et qu'il peut donc être compté dans une métrique.
        assertThat(branch.indexOf("999")).isEqualTo(-1);
    }

    @Test
    void buildsAnIndexedLineFromTheGeometry() {
        LineBranch branch = LineBranch.of("SH9", (short) 0, "Gamma", line(), List.of());

        // Préconstruit une fois pour toutes : /vehicles le rebâtissait à chaque requête.
        assertThat(branch.indexed()).isNotNull();
        assertThat(branch.indexed().getEndIndex()).isGreaterThan(0.0);
    }
}
```

`backend/src/test/java/com/mapidf/network/NetworkSnapshotTest.java` :

```java
package com.mapidf.network;

import java.util.List;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class NetworkSnapshotTest {

    private static TrackedLine line(String id, String siriRef) {
        return new TrackedLine(id, "IDFM:C0" + id, siriRef, id, "#000000", "METRO", List.of());
    }

    @Test
    void indexesLinesByPublicIdAndBySiriRef() {
        NetworkSnapshot snapshot = NetworkSnapshot.of(
            List.of(line("9", "STIF:Line::C01379:"), line("7", "STIF:Line::C01377:")),
            List.of());

        assertThat(snapshot.linesById().get("9").siriLineRef()).isEqualTo("STIF:Line::C01379:");
        assertThat(snapshot.linesBySiriRef().get("STIF:Line::C01377:").id()).isEqualTo("7");
    }

    @Test
    void indexesStationsById() {
        Station station = new Station("STC", "Correspondance", 48.850, 2.310,
            List.of("S2", "P2"), List.of("7", "9"));

        NetworkSnapshot snapshot = NetworkSnapshot.of(List.of(), List.of(station));

        assertThat(snapshot.stationsById().get("STC").lineIds()).containsExactly("7", "9");
    }

    @Test
    void emptySnapshotHasNoLineAndNoStation() {
        assertThat(NetworkSnapshot.empty().lines()).isEmpty();
        assertThat(NetworkSnapshot.empty().stations()).isEmpty();
        assertThat(NetworkSnapshot.empty().linesById()).isEmpty();
    }
}
```

- [ ] **Step 2: Lancer les tests pour vérifier qu'ils échouent**

Run: `cd backend && ./mvnw test -Dtest='LineBranchTest,NetworkSnapshotTest'`
Expected: FAIL — `cannot find symbol: class LineBranch`

- [ ] **Step 3: Implémenter le modèle en mémoire**

`backend/src/main/java/com/mapidf/network/LineBranch.java` :

```java
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

    /** Rang de l'arrêt dans cette branche, ou −1 s'il n'y figure pas. */
    public int indexOf(String stopKey) {
        return indexByStopKey.getOrDefault(stopKey, -1);
    }
}
```

`backend/src/main/java/com/mapidf/network/TrackedLine.java` :

```java
package com.mapidf.network;

import java.util.List;

/** Une ligne suivie et ses branches (1 par sens pour 13 des 16 lignes, 2 pour la 7 et la 13). */
public record TrackedLine(String id, String gtfsRouteId, String siriLineRef,
                          String shortName, String color, String mode, List<LineBranch> branches) {
    public TrackedLine {
        branches = List.copyOf(branches);
    }
}
```

`backend/src/main/java/com/mapidf/network/Station.java` :

```java
package com.mapidf.network;

import java.util.List;

/**
 * Une station physique, dédoublonnée depuis ses quais. Mesuré sur le métro : 781 quais →
 * 321 stations, dont 61 correspondances (jusqu'à 5 lignes à République et Châtelet).
 */
public record Station(String id, String name, double lat, double lng,
                      List<String> platformIds, List<String> lineIds) {
    public Station {
        platformIds = List.copyOf(platformIds);
        lineIds = List.copyOf(lineIds);
    }
}
```

`backend/src/main/java/com/mapidf/network/NetworkSnapshot.java` :

```java
package com.mapidf.network;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * État réseau immuable, publié en bloc par {@link LineRegistry}. Les index de résolution sont
 * construits une fois à la fabrication : aucune requête n'a besoin de la base.
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
```

- [ ] **Step 4: Lancer les tests pour vérifier qu'ils passent**

Run: `cd backend && ./mvnw test -Dtest='LineBranchTest,NetworkSnapshotTest'`
Expected: PASS (6 tests)

- [ ] **Step 5: Écrire l'IT du builder, qui échoue**

`backend/src/test/java/com/mapidf/network/NetworkRegistryBuilderIT.java` :

```java
package com.mapidf.network;

import com.mapidf.MapIdfTest;
import com.mapidf.gtfs.GtfsStaticLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import static org.assertj.core.api.Assertions.assertThat;

@MapIdfTest
class NetworkRegistryBuilderIT {

    @Autowired GtfsStaticLoader loader;
    @Autowired NetworkRegistryBuilder builder;

    @BeforeEach
    void loadFixture() throws Exception {
        try (var in = getClass().getResourceAsStream("/gtfs-branch.zip")) {
            loader.load(in);
        }
    }

    @Test
    void buildsTheTwoTrackedLinesWithTheirBranches() {
        NetworkSnapshot snapshot = builder.build();

        assertThat(snapshot.lines()).extracting(TrackedLine::id)
            .containsExactlyInAnyOrder("9", "7");
        assertThat(snapshot.linesById().get("9").branches()).hasSize(2);
        assertThat(snapshot.linesById().get("7").branches()).hasSize(2);
        assertThat(snapshot.linesBySiriRef()).containsKey("STIF:Line::C01379:");
    }

    @Test
    void projectsEachBranchStopsOntoItsOwnGeometry() {
        NetworkSnapshot snapshot = builder.build();

        LineBranch villejuif = snapshot.linesById().get("7").branches().stream()
            .filter(b -> b.shapeId().equals("SH7A")).findFirst().orElseThrow();

        // 4 arrêts projetés, distances croissantes le long du tracé : cette monotonie est ce
        // qui rend l'interpolation correcte. Avec un tracé unique pour les deux branches, P4
        // se projetterait à ~1,5 km de sa position réelle.
        assertThat(villejuif.stops()).hasSize(4);
        assertThat(villejuif.stops()).extracting(StopOnLineDistance::of).isSorted();
        assertThat(villejuif.indexOf("4")).isEqualTo(3);
        assertThat(villejuif.indexOf("5")).isEqualTo(-1);
    }

    @Test
    void namesTheTerminusOfEachBranch() {
        NetworkSnapshot snapshot = builder.build();

        assertThat(snapshot.linesById().get("7").branches())
            .extracting(LineBranch::terminusName)
            .containsExactlyInAnyOrder("Villejuif", "Ivry");
    }

    @Test
    void deduplicatesStationsAndListsTheirLines() {
        NetworkSnapshot snapshot = builder.build();

        // 7 stations : ST1, STC, ST3 (ligne 9) + PT1, PT3, PT4, PT5 (ligne 7), STC partagée.
        assertThat(snapshot.stations()).hasSize(7);

        Station correspondence = snapshot.stationsById().get("STC");
        assertThat(correspondence.name()).isEqualTo("Correspondance");
        assertThat(correspondence.lineIds()).containsExactly("7", "9");
        assertThat(correspondence.platformIds()).containsExactlyInAnyOrder("S2", "P2");
    }

    @Test
    void takesStationCoordinatesFromTheParentStop() {
        NetworkSnapshot snapshot = builder.build();

        // Le parent porte ses propres coordonnées : plus de centroïde de quais, et un nom
        // déterministe (il venait du premier quai rencontré, d'où le ticket connu).
        Station correspondence = snapshot.stationsById().get("STC");
        assertThat(correspondence.lat()).isEqualTo(48.850);
        assertThat(correspondence.lng()).isEqualTo(2.310);
    }

    /** Extracteur nommé : AssertJ ne sait pas trier sur un double extrait par lambda. */
    private interface StopOnLineDistance {
        static Double of(com.mapidf.position.StopOnLine stop) {
            return stop.distanceAlongLine();
        }
    }
}
```

- [ ] **Step 6: Lancer l'IT pour vérifier qu'il échoue**

Run: `cd backend && ./mvnw verify -Dit.test=NetworkRegistryBuilderIT`
Expected: FAIL — `cannot find symbol: class NetworkRegistryBuilder`

- [ ] **Step 7: Implémenter `NetworkRegistryBuilder`**

`backend/src/main/java/com/mapidf/network/NetworkRegistryBuilder.java` :

```java
package com.mapidf.network;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;

import com.mapidf.data.entity.Branch;
import com.mapidf.data.entity.Route;
import com.mapidf.data.entity.Stop;
import com.mapidf.data.entity.StopTime;
import com.mapidf.data.repositories.BranchRepository;
import com.mapidf.data.repositories.StopRepository;
import com.mapidf.data.repositories.StopTimeRepository;
import com.mapidf.position.PositionEngine;
import com.mapidf.position.StopOnLine;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.locationtech.jts.linearref.LengthIndexedLine;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Construit un {@link NetworkSnapshot} depuis PostGIS. Appelé au démarrage (réhydratation, pour
 * ne pas retélécharger 109 Mo de GTFS) et après chaque refresh quotidien — jamais sur le chemin
 * d'une requête.
 *
 * <p>Deux requêtes seulement, toutes deux à {@code JOIN FETCH} explicite : en chargement
 * paresseux, 37 branches × leurs stop_times × leurs arrêts feraient une centaine de requêtes.
 */
@Slf4j
@Service
@AllArgsConstructor
public class NetworkRegistryBuilder {

    private final BranchRepository branchRepository;
    private final StopTimeRepository stopTimeRepository;
    private final StopRepository stopRepository;

    @Transactional(readOnly = true)
    public NetworkSnapshot build() {
        List<Branch> branches = branchRepository.findAllWithRoute();
        List<StopTime> stopTimes = stopTimeRepository.findAllForRegistry();

        Map<UUID, List<StopTime>> byBranch = new LinkedHashMap<>();
        stopTimes.forEach(st -> byBranch
            .computeIfAbsent(st.getBranch().getId(), key -> new ArrayList<>()).add(st));

        Map<String, List<LineBranch>> branchesByRoute = new LinkedHashMap<>();
        Map<String, Route> routesByGtfsId = new LinkedHashMap<>();
        Map<String, TreeSet<String>> platformsByStation = new LinkedHashMap<>();
        Map<String, TreeSet<String>> lineIdsByStation = new LinkedHashMap<>();

        for (Branch branch : branches) {
            Route route = branch.getRoute();
            routesByGtfsId.putIfAbsent(route.getGtfsId(), route);
            // Chaque branche projette SES arrêts sur SA géométrie : c'est ce qui empêche un
            // arrêt de branche de se projeter n'importe où sur la branche voisine.
            LengthIndexedLine indexed = new LengthIndexedLine(branch.getGeom());
            List<StopTime> ordered = byBranch.getOrDefault(branch.getId(), List.of());

            List<StopOnLine> stops = ordered.stream()
                .map(st -> new StopOnLine(
                    PositionEngine.stopKey(st.getStop().getGtfsId()),
                    st.getStop().getName(),
                    indexed.project(st.getStop().getGeom().getCoordinate()),
                    st.getDepartureSec()))
                .toList();

            branchesByRoute.computeIfAbsent(route.getGtfsId(), key -> new ArrayList<>())
                .add(LineBranch.of(branch.getGtfsShapeId(), branch.getDirection(),
                    branch.getTerminusName(), branch.getGeom(), stops));

            String lineId = publicId(route.getShortName());
            for (StopTime st : ordered) {
                String stationId = stationKey(st.getStop());
                platformsByStation.computeIfAbsent(stationId, key -> new TreeSet<>())
                    .add(st.getStop().getGtfsId());
                lineIdsByStation.computeIfAbsent(stationId, key -> new TreeSet<>()).add(lineId);
            }
        }

        List<TrackedLine> lines = routesByGtfsId.values().stream()
            .map(route -> new TrackedLine(
                publicId(route.getShortName()), route.getGtfsId(), route.getSiriLineRef(),
                route.getShortName(), route.getColor(), route.getMode(),
                branchesByRoute.getOrDefault(route.getGtfsId(), List.of())))
            .sorted(Comparator.comparing(TrackedLine::id))
            .toList();

        List<Station> stations = buildStations(platformsByStation, lineIdsByStation);

        log.info("[REGISTRY] {} ligne(s), {} branche(s), {} station(s)",
            lines.size(),
            lines.stream().mapToInt(line -> line.branches().size()).sum(),
            stations.size());
        return NetworkSnapshot.of(lines, stations);
    }

    private List<Station> buildStations(Map<String, TreeSet<String>> platformsByStation,
                                        Map<String, TreeSet<String>> lineIdsByStation) {
        // Les stations parentes sont persistées comme arrêts à part entière : elles portent leur
        // propre nom et leurs propres coordonnées. Mesuré : les 781 quais du métro ont tous un
        // parent présent en location_type=1, donc le repli sur le quai ne sert pas au métro.
        Map<String, Stop> byGtfsId = new LinkedHashMap<>();
        stopRepository.findByGtfsIdIn(platformsByStation.keySet())
            .forEach(stop -> byGtfsId.put(stop.getGtfsId(), stop));

        List<Station> stations = new ArrayList<>();
        platformsByStation.forEach((stationId, platforms) -> {
            Stop reference = byGtfsId.get(stationId);
            if (reference == null) {
                log.warn("[REGISTRY] station {} introuvable, ignorée", stationId);
                return;
            }
            stations.add(new Station(stationId, reference.getName(),
                reference.getGeom().getY(), reference.getGeom().getX(),
                List.copyOf(platforms),
                List.copyOf(lineIdsByStation.getOrDefault(stationId, new TreeSet<>()))));
        });
        return List.copyOf(stations);
    }

    private static String stationKey(Stop stop) {
        String parent = stop.getParentStation();
        return (parent == null || parent.isBlank()) ? stop.getGtfsId() : parent;
    }

    private static String publicId(String shortName) {
        return shortName == null ? "" : shortName.trim().toLowerCase().replace(" ", "");
    }
}
```

- [ ] **Step 8: Implémenter `LineRegistry`**

`backend/src/main/java/com/mapidf/network/LineRegistry.java` :

```java
package com.mapidf.network;

import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import com.mapidf.data.enums.ErrorCode;
import com.mapidf.exceptions.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Source unique de vérité du réseau suivi. L'état est publié en bloc par un
 * {@link AtomicReference} : aucune requête ne voit un réseau à moitié rebâti.
 */
@Component
public class LineRegistry {

    private final AtomicReference<NetworkSnapshot> snapshot =
        new AtomicReference<>(NetworkSnapshot.empty());

    public NetworkSnapshot current() {
        return snapshot.get();
    }

    public void publish(NetworkSnapshot next) {
        snapshot.set(next);
    }

    public TrackedLine requireLine(String id) {
        TrackedLine line = current().linesById().get(id);
        if (line == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, ErrorCode.LINE_NOT_FOUND);
        }
        return line;
    }

    public Station requireStation(String id) {
        Station station = current().stationsById().get(id);
        if (station == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, ErrorCode.STATION_NOT_FOUND);
        }
        return station;
    }

    /** LineRef SIRI des lignes suivies : sert à filtrer le flux global au fil de l'eau. */
    public Set<String> trackedSiriLineRefs() {
        return current().linesBySiriRef().keySet();
    }
}
```

- [ ] **Step 9: Lancer l'IT puis la suite complète**

Run: `cd backend && ./mvnw verify -Dit.test=NetworkRegistryBuilderIT`
Expected: PASS (5 tests)

Run: `cd backend && ./mvnw verify`
Expected: BUILD SUCCESS

- [ ] **Step 10: Commit**

```bash
cd /home/abodet/workspace/perso/MapIDF
git add backend/src/main/java/com/mapidf/network backend/src/test/java/com/mapidf/network
git commit -m "feat(back): registry réseau en mémoire, source unique de vérité

LineRegistry publie un NetworkSnapshot immuable par échange atomique :
lignes, branches avec géométrie indexée et arrêts projetés, stations
dédoublonnées portant leurs lignes desservantes.

Sort la base du chemin de requête : les endpoints n'auront plus aucune
requête SQL à faire. PostGIS n'est lu qu'au démarrage (réhydratation, pour
ne pas retélécharger 109 Mo de GTFS) et après le refresh quotidien, en
deux requêtes à JOIN FETCH explicite — en chargement paresseux, 37
branches et leurs arrêts feraient une centaine de requêtes.

Chaque branche porte son LengthIndexedLine préconstruit (/vehicles le
rebâtissait à chaque requête) et une Map<stopKey, index> : sans elle le
choix de branche coûterait ~100 000 comparaisons de chaînes par requête."
```

---

## Task 7: `GtfsStaticService` publie le registry

**Files:**
- Modify: `backend/src/main/java/com/mapidf/gtfs/GtfsStaticService.java`
- Create: `backend/src/test/java/com/mapidf/gtfs/GtfsStaticServiceIT.java`

**Interfaces:**
- Consumes: `GtfsStaticLoader.load(InputStream)` (tâche 5), `NetworkRegistryBuilder.build()` et `LineRegistry.publish(...)` (tâche 6).
- Produces: `GtfsStaticService.publishFromDatabase()` — réhydrate le registry sans accès réseau ; **remplace** l'ancien `cacheGeometry()`.

- [ ] **Step 1: Écrire l'IT qui échoue**

`backend/src/test/java/com/mapidf/gtfs/GtfsStaticServiceIT.java` :

```java
package com.mapidf.gtfs;

import com.mapidf.MapIdfTest;
import com.mapidf.network.LineRegistry;
import com.mapidf.network.TrackedLine;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import static org.assertj.core.api.Assertions.assertThat;

@MapIdfTest
class GtfsStaticServiceIT {

    @Autowired GtfsStaticLoader loader;
    @Autowired GtfsStaticService service;
    @Autowired LineRegistry registry;

    @Test
    void republishesTheRegistryFromTheDatabaseWithoutNetworkAccess() throws Exception {
        try (var in = getClass().getResourceAsStream("/gtfs-branch.zip")) {
            loader.load(in);
        }

        service.publishFromDatabase();

        // C'est le chemin emprunté à chaque redémarrage, sans retélécharger les 109 Mo.
        assertThat(registry.current().lines()).extracting(TrackedLine::id)
            .containsExactlyInAnyOrder("7", "9");
        assertThat(registry.trackedSiriLineRefs())
            .contains("STIF:Line::C01379:", "STIF:Line::C01377:");
    }

    @Test
    void refreshLeavesTheRegistryUntouchedWhenNoStaticUrlIsConfigured() throws Exception {
        try (var in = getClass().getResourceAsStream("/gtfs-branch.zip")) {
            loader.load(in);
        }
        service.publishFromDatabase();
        NetworkSnapshot before = registry.current();

        // Profil test : app.prim.gtfs-static-url est vide, refresh() doit sortir immédiatement
        // sans lever, sans accès réseau et SANS republier.
        service.refresh();

        assertThat(registry.current()).isSameAs(before);
    }
}
```

**Attention, piège de test** : `LineRegistry` est un singleton dont l'état **survit au rollback transactionnel** des IT (il est en mémoire, pas en base) et à l'enchaînement des tests. Un test ne doit donc jamais supposer un registry vide : chaque IT qui le lit publie d'abord son propre état, comme ci-dessus. C'est aussi pour cette raison que `hydrateOnStartup` ne doit rien casser quand la base est vide au démarrage du contexte de test.

- [ ] **Step 2: Lancer l'IT pour vérifier qu'il échoue**

Run: `cd backend && ./mvnw verify -Dit.test=GtfsStaticServiceIT`
Expected: FAIL — `cannot find symbol: method publishFromDatabase()`

- [ ] **Step 3: Adapter `GtfsStaticService`**

Injecter `NetworkRegistryBuilder registryBuilder` et `LineRegistry registry` (et retirer le champ `RouteRepository`, devenu inutile). Ajouter :

```java
    /**
     * Republie le registry depuis PostGIS, sans accès réseau. Appelé au démarrage : un
     * redémarrage ne doit pas imposer de retélécharger 109 Mo de GTFS.
     */
    public void publishFromDatabase() {
        registry.publish(registryBuilder.build());
    }

    /**
     * Réhydrate le registry dès le démarrage, avant que le refresh quotidien n'ait abouti :
     * l'API répond immédiatement avec le dernier réseau connu au lieu de renvoyer des 404
     * pendant le téléchargement.
     */
    @PostConstruct
    void hydrateOnStartup() {
        try {
            publishFromDatabase();
        } catch (Exception e) {
            log.warn("[GTFS] Réhydratation au démarrage impossible (base vide ?) : {}", e.getMessage());
        }
    }
```

(import `jakarta.annotation.PostConstruct`.) Dans `refresh()`, remplacer la fin du `try` par :

```java
            loader.load(response.body());
            publishFromDatabase();
            log.info("[GTFS] Réseau rechargé et registry republié");
```

- [ ] **Step 4: Lancer l'IT puis la suite complète**

Run: `cd backend && ./mvnw verify -Dit.test=GtfsStaticServiceIT`
Expected: PASS (2 tests)

Run: `cd backend && ./mvnw verify`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
cd /home/abodet/workspace/perso/MapIDF
git add backend/src/main/java/com/mapidf/gtfs/GtfsStaticService.java \
        backend/src/test/java/com/mapidf/gtfs/GtfsStaticServiceIT.java
git commit -m "feat(back): publication du registry au chargement et au démarrage

refresh() republie le registry après chaque chargement GTFS, et
publishFromDatabase() le réhydrate depuis PostGIS au démarrage sans accès
réseau : un redémarrage ne doit pas imposer de retélécharger 109 Mo, et
l'API répond avec le dernier réseau connu au lieu de renvoyer des 404
pendant le téléchargement."
```

---

## Task 8: Ingestion temps réel — gzip, parse en streaming, `recordedAt`

On retire `?LineRef=` : le flux devient global, un seul appel par poll, quota inchangé. Mesuré le 2026-07-29 : **45,6 Mo de JSON, 3,96 Mo transférés en gzip (×11,5), 5,8 s**, 12 018 courses sur 1 013 lignes dont **705 pour le métro**.

Trois changements, tous nécessaires. Sans le streaming, `readTree` sur 45,6 Mo alloue le `byte[]` **et** l'arbre complet. Sans gzip, c'est ~55 Go/jour au lieu de ~4,7.

**Files:**
- Modify: `backend/src/main/java/com/mapidf/rt/RealtimePoller.java`
- Modify: `backend/src/main/java/com/mapidf/rt/RtSnapshot.java`
- Modify: `backend/src/test/java/com/mapidf/rt/RtFixtures.java`
- Modify: `backend/src/test/java/com/mapidf/rt/RealtimePollerParseTest.java`
- Modify: `backend/src/test/java/com/mapidf/rt/RealtimePollerResilienceTest.java`

**Interfaces:**
- Consumes: `LineRegistry.trackedSiriLineRefs()` (tâche 6).
- Produces: `RtSnapshot.LiveJourney` gagne un composant final `Instant recordedAt` — signature `LiveJourney(String lineRef, String journeyRef, String directionRef, String destination, Instant recordedAt, List<Call> calls)`.
- Produces: `RealtimePoller.Fetcher.get(String url)` renvoie désormais `InputStream` (et non `byte[]`).
- Produces: `static RtSnapshot RealtimePoller.parse(ObjectMapper mapper, InputStream body, Instant asOf, Set<String> trackedLineRefs)`.

- [ ] **Step 1: Étendre `LiveJourney` avec `recordedAt`**

Dans `RtSnapshot.java`, remplacer la déclaration du record imbriqué par :

```java
    /**
     * Une course temps réel = son identité + la liste de ses arrêts estimés (dans l'ordre du
     * flux, PAS trié).
     *
     * <p>{@code recordedAt} est l'horodatage de dernière mise à jour de la course, présent sur
     * les 705 courses métro mesurées le 2026-07-29 (médiane 0,4 min, max 16,8 min d'âge).
     * Information affichée telle quelle : ce n'est PAS un critère d'atténuation — mesuré sur
     * une ligne 8 en perturbation, c'était la ligne à la donnée la plus fraîche du réseau.
     */
    public record LiveJourney(String lineRef, String journeyRef, String directionRef,
                              String destination, Instant recordedAt, List<Call> calls) {
```

- [ ] **Step 2: Écrire les tests qui échouent**

Ajouter à `RtFixtures.java` :

```java
    static java.io.InputStream stream(byte[] json) {
        return new java.io.ByteArrayInputStream(json);
    }

    /** Corps gzippé, comme PRIM le renvoie quand on envoie Accept-Encoding: gzip. */
    static byte[] gzip(byte[] raw) throws java.io.IOException {
        var out = new java.io.ByteArrayOutputStream();
        try (var gz = new java.util.zip.GZIPOutputStream(out)) {
            gz.write(raw);
        }
        return out.toByteArray();
    }

    // Course ligne 9 portant un RecordedAtTime plus vieux que la réponse (cas mesuré : 9 min
    // d'écart sur une course à un seul appel).
    static byte[] siriStaleJourneySample() {
        String json = """
            {"Siri":{"ServiceDelivery":{"ResponseTimestamp":"2026-07-22T14:00:00.000Z",
              "EstimatedTimetableDelivery":[{"EstimatedJourneyVersionFrame":[{
                "EstimatedVehicleJourney":[{
                  "RecordedAtTime":"2026-07-22T13:51:00.000Z",
                  "LineRef":{"value":"STIF:Line::C01379:"},
                  "DirectionRef":{"value":"0"},
                  "DatedVehicleJourneyRef":{"value":"J1"},
                  "DestinationName":[{"value":"Gamma"}],
                  "EstimatedCalls":{"EstimatedCall":[{
                    "StopPointRef":{"value":"STIF:StopPoint:Q:2:"},
                    "ExpectedDepartureTime":"2026-07-22T14:05:00.000Z",
                    "DepartureStatus":"ON_TIME"
                  }]}
                }]
              }]}]
            }}}
            """;
        return json.getBytes(StandardCharsets.UTF_8);
    }
```

Ajouter à `RealtimePollerParseTest.java` (et adapter les tests existants : ils appellent désormais `parse(mapper, RtFixtures.stream(...), asOf, Set.of("STIF:Line::C01379:"))`) :

```java
    private static final String LINE_NINE = "STIF:Line::C01379:";
    private static final String LINE_ONE = "STIF:Line::C01371:";

    @Test
    void keepsOnlyTheTrackedLinesOfTheGlobalFeed() {
        // Le flux global couvre 1 013 lignes pour 12 018 courses ; on n'en matérialise que
        // celles du périmètre, au fil de l'eau.
        RtSnapshot snapshot = RealtimePoller.parse(
            new ObjectMapper(), RtFixtures.stream(RtFixtures.siriMultiLineSample()),
            Instant.parse("2026-07-22T14:00:00Z"), Set.of(LINE_NINE));

        assertThat(snapshot.byLine()).containsOnlyKeys(LINE_NINE);
        assertThat(snapshot.forLine(LINE_ONE)).isEmpty();
    }

    @Test
    void keepsSeveralLinesWhenSeveralAreTracked() {
        RtSnapshot snapshot = RealtimePoller.parse(
            new ObjectMapper(), RtFixtures.stream(RtFixtures.siriMultiLineSample()),
            Instant.parse("2026-07-22T14:00:00Z"), Set.of(LINE_NINE, LINE_ONE));

        assertThat(snapshot.byLine()).containsOnlyKeys(LINE_NINE, LINE_ONE);
        assertThat(snapshot.forLine(LINE_ONE)).singleElement()
            .extracting(RtSnapshot.LiveJourney::destination).isEqualTo("Delta");
    }

    @Test
    void keepsNothingWhenNoLineIsTrackedYet() {
        // Registry pas encore réhydraté : on ne sait pas quoi suivre, donc on ne matérialise
        // rien plutôt que d'ingérer les 12 018 courses du réseau. Le poll suivant (≤60 s)
        // reprendra avec un registry rempli.
        RtSnapshot snapshot = RealtimePoller.parse(
            new ObjectMapper(), RtFixtures.stream(RtFixtures.siriMultiLineSample()),
            Instant.parse("2026-07-22T14:00:00Z"), Set.of());

        assertThat(snapshot.byLine()).isEmpty();
    }

    @Test
    void readsTheRecordedAtTimeOfEachJourney() {
        RtSnapshot snapshot = RealtimePoller.parse(
            new ObjectMapper(), RtFixtures.stream(RtFixtures.siriStaleJourneySample()),
            Instant.parse("2026-07-22T14:00:00Z"), Set.of(LINE_NINE));

        assertThat(snapshot.forLine(LINE_NINE)).singleElement()
            .extracting(RtSnapshot.LiveJourney::recordedAt)
            .isEqualTo(Instant.parse("2026-07-22T13:51:00Z"));
    }

    @Test
    void toleratesAJourneyWithoutRecordedAtTime() {
        // Les fixtures historiques n'en portent pas : l'absence ne doit pas perdre la course.
        RtSnapshot snapshot = RealtimePoller.parse(
            new ObjectMapper(), RtFixtures.stream(RtFixtures.siriMultiLineSample()),
            Instant.parse("2026-07-22T14:00:00Z"), Set.of(LINE_NINE));

        assertThat(snapshot.forLine(LINE_NINE)).singleElement()
            .extracting(RtSnapshot.LiveJourney::recordedAt).isNull();
    }
```

Ajouter à `RealtimePollerResilienceTest.java` (et adapter les `Fetcher` existants pour renvoyer `RtFixtures.stream(...)`) :

```java
    @Test
    void decodesAGzippedBody() throws Exception {
        // Mesuré : PRIM répond Content-Encoding: gzip, 3,96 Mo au lieu de 45,6 Mo.
        byte[] gzipped = RtFixtures.gzip(RtFixtures.siriMultiLineSample());
        RtSnapshot snapshot = RealtimePoller.parse(
            new ObjectMapper(), new java.util.zip.GZIPInputStream(RtFixtures.stream(gzipped)),
            Instant.parse("2026-07-22T14:00:00Z"), Set.of("STIF:Line::C01379:"));

        assertThat(snapshot.forLine("STIF:Line::C01379:")).hasSize(1);
    }
```

- [ ] **Step 3: Lancer les tests pour vérifier qu'ils échouent**

Run: `cd backend && ./mvnw test -Dtest='RealtimePoller*Test'`
Expected: FAIL — `parse(ObjectMapper, InputStream, Instant, Set)` introuvable

- [ ] **Step 4: Réécrire `RealtimePoller`**

Injecter `LineRegistry registry` (et retirer `LineProperties line`, supprimé en tâche 4). Remplacer `Fetcher`, `pollOnce`, `buildUrl`, `fetch`, `parse` et `toJourney` par :

```java
    @FunctionalInterface
    public interface Fetcher {
        InputStream get(String url) throws Exception;
    }

    // Mesuré le 2026-07-29 : 5,8 s pour 3,96 Mo. On garde une marge confortable tout en
    // restant nettement sous l'intervalle de poll (60 s), pour ne jamais chevaucher.
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(45);

    void pollOnce(Fetcher fetcher, Instant asOf) {
        try (InputStream body = fetcher.get(prim.realtimeBaseUrl())) {
            snapshot.set(parse(objectMapper, body, asOf, registry.trackedSiriLineRefs()));
            log.info("[RT] Poll réussi");
        } catch (Exception e) {
            if (pollFailures != null) {
                pollFailures.increment();
            }
            log.warn("[RT] Échec du poll, snapshot conservé : {}", e.getMessage());
        }
    }

    private InputStream fetch(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
            .header(prim.authHeader(), prim.apiKey())
            // Le HttpClient de Java ne négocie pas gzip tout seul et ne décompresse pas : on
            // demande explicitement et on décode. Mesuré : 3,96 Mo au lieu de 45,6 Mo (×11,5),
            // soit ~4,7 Go/jour au lieu de ~55.
            .header("Accept-Encoding", "gzip")
            .timeout(REQUEST_TIMEOUT)
            .GET()
            .build();
        HttpResponse<InputStream> response =
            httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        // On DOIT vérifier le code HTTP : sur 429 (quota) ou 5xx, PRIM renvoie un corps JSON
        // d'erreur qui se parserait en 0 course et écraserait le dernier bon snapshot.
        if (response.statusCode() / 100 != 2) {
            response.body().close();
            throw new IOException("réponse HTTP " + response.statusCode() + " de PRIM");
        }
        boolean gzipped = response.headers().firstValue("Content-Encoding")
            .map(value -> value.toLowerCase(java.util.Locale.ROOT).contains("gzip"))
            .orElse(false);
        return gzipped ? new GZIPInputStream(response.body()) : response.body();
    }

    /**
     * Parse le flux SIRI global en streaming : on avance jusqu'aux {@code
     * EstimatedVehicleJourney} et on lit UNE course à la fois en sous-arbre, gardée seulement si
     * son {@code LineRef} est suivi. Pic mémoire = une course, au lieu des 45,6 Mo du corps plus
     * l'arbre complet qu'imposait {@code readTree(byte[])}.
     *
     * <p>Un ensemble de lignes vide (registry pas encore réhydraté) ne matérialise rien : le
     * poll suivant reprendra avec un registry rempli.
     */
    static RtSnapshot parse(ObjectMapper mapper, InputStream body, Instant asOf,
                            Set<String> trackedLineRefs) {
        Map<String, List<RtSnapshot.LiveJourney>> byLine = new HashMap<>();
        try (JsonParser parser = mapper.createParser(body)) {
            while (parser.nextToken() != null) {
                if (parser.currentToken() != JsonToken.PROPERTY_NAME
                    || !"EstimatedVehicleJourney".equals(parser.currentName())) {
                    continue;
                }
                JsonToken value = parser.nextToken();
                if (value == JsonToken.START_ARRAY) {
                    while (parser.nextToken() != JsonToken.END_ARRAY) {
                        collect(mapper.readTree(parser), trackedLineRefs, byLine);
                    }
                } else if (value == JsonToken.START_OBJECT) {
                    collect(mapper.readTree(parser), trackedLineRefs, byLine);
                }
            }
        }
        return new RtSnapshot(asOf, byLine);
    }

    private static void collect(JsonNode journey, Set<String> trackedLineRefs,
                                Map<String, List<RtSnapshot.LiveJourney>> byLine) {
        String lineRef = journey.path("LineRef").path("value").asString("");
        if (!trackedLineRefs.contains(lineRef)) {
            return;
        }
        try {
            RtSnapshot.LiveJourney live = toJourney(journey, lineRef);
            if (live != null) {
                byLine.computeIfAbsent(lineRef, key -> new ArrayList<>()).add(live);
            }
        } catch (RuntimeException e) {
            // Une course pourrie ne doit pas faire perdre tout le snapshot — surtout en réseau
            // complet, où une seule course cassée coûterait les 705 autres.
            log.warn("[RT] Course ignorée (parse impossible) : {}", e.getMessage());
        }
    }

    private static RtSnapshot.LiveJourney toJourney(JsonNode journey, String lineRef) {
        List<RtSnapshot.LiveJourney.Call> calls = new ArrayList<>();
        for (JsonNode call : callList(journey.path("EstimatedCalls").path("EstimatedCall"))) {
            String stopRef = call.path("StopPointRef").path("value").asString(null);
            Instant time = callTime(call);
            if (stopRef == null || time == null) {
                continue;
            }
            calls.add(new RtSnapshot.LiveJourney.Call(
                stopRef, time, call.path("DepartureStatus").asString("")));
        }
        if (calls.isEmpty()) {
            return null;
        }
        String directionRef = journey.path("DirectionRef").path("value").asString("");
        String destination = firstValue(journey.path("DestinationName"));
        String recordedAtRaw = journey.path("RecordedAtTime").asString(null);
        Instant recordedAt = recordedAtRaw == null ? null : Instant.parse(recordedAtRaw);
        // Mesuré le 2026-07-29 : DatedVehicleJourneyRef est renseigné sur les 705 courses
        // métro, donc l'identité est stable entre deux polls (ce qui fait vivre l'animation
        // à 705 véhicules). Le repli composite ne sert qu'aux modes moins bien renseignés.
        String journeyRef = journey.path("DatedVehicleJourneyRef").path("value").asString(null);
        if (journeyRef == null) {
            journeyRef = lineRef + "|" + directionRef + "|" + destination + "|" + calls.getFirst().time();
        }
        return new RtSnapshot.LiveJourney(lineRef, journeyRef, directionRef, destination,
            recordedAt, calls);
    }
```

Imports à ajouter : `java.io.InputStream`, `java.util.Set`, `java.util.zip.GZIPInputStream`, `tools.jackson.core.JsonParser`, `tools.jackson.core.JsonToken`. Supprimer `java.net.URLEncoder` et `java.nio.charset.StandardCharsets` (le filtre `?LineRef=` disparaît).

Mettre à jour aussi le commentaire de `poll()` pour dire que le flux est global et que le périmètre vient du registry.

- [ ] **Step 5: Lancer les tests puis la suite complète**

Run: `cd backend && ./mvnw test -Dtest='RealtimePoller*Test'`
Expected: PASS

Run: `cd backend && ./mvnw verify`
Expected: BUILD SUCCESS

- [ ] **Step 6: Commit**

```bash
cd /home/abodet/workspace/perso/MapIDF
git add backend/src/main/java/com/mapidf/rt backend/src/test/java/com/mapidf/rt
git commit -m "feat(back): flux SIRI global en gzip et parse en streaming

Retire le filtre ?LineRef= : un seul appel couvre tout le réseau, le
périmètre étant appliqué à la lecture depuis les LineRef du registry.

Mesuré le 2026-07-29 sur le flux réel : 45,6 Mo de JSON, 12 018 courses
sur 1 013 lignes dont 705 pour le métro.

- Accept-Encoding: gzip -> 3,96 Mo transférés (x11,5), soit ~4,7 Go/jour
  au lieu de ~55. Le HttpClient de Java ne le négocie pas seul.
- Parse en streaming, une course à la fois : readTree sur 45,6 Mo allouait
  le tableau d'octets ET l'arbre complet.
- Timeout porté à 45 s (5,8 s mesurés), en restant sous l'intervalle de
  poll de 60 s.

Expose aussi RecordedAtTime, présent sur les 705 courses métro et jusqu'ici
ignoré. Information d'affichage seulement : mesuré sur une ligne 8 en
perturbation, c'était la ligne à la donnée la plus fraîche du réseau, donc
ce n'est pas un signal de fiabilité."
```

---

## Task 9: `PositionEngine` — choix de branche et indicateur de confiance

Le moteur reçoit désormais une ligne et choisit **la branche** avant d'interpoler. `pickDirection` faisait déjà exactement le bon travail — filtrer les candidats contenant l'arrêt, puis départager par terminus : on généralise de « les sens d'une ligne » à « les branches d'une ligne ».

`Vehicle` gagne l'indicateur de confiance. Le signal est **structurel** : une course à un seul `EstimatedCall` est mal plaçable (le train est borné à l'arrêt précédant son unique appel, souvent un terminus lointain). Mesuré : 254 courses sur 705, soit **36 %**. Aucune ETA n'entre dans ce calcul.

**Files:**
- Modify: `backend/src/main/java/com/mapidf/position/Vehicle.java`
- Modify: `backend/src/main/java/com/mapidf/position/PositionEngine.java`
- Modify: `backend/src/test/java/com/mapidf/position/PositionEngineTest.java`

**Interfaces:**
- Consumes: `TrackedLine`, `LineBranch` (tâche 6), `RtSnapshot.LiveJourney` avec `recordedAt` (tâche 8).
- Produces: `Vehicle` record — `String journeyRef, String lineId, double lat, double lng, double bearing, String status, String headsign, String nextStop, Instant expectedTime, Instant recordedAt, Source source, Confidence confidence` ; enums `Source {REALTIME, INTERPOLATED}` et `Confidence {RELIABLE, APPROXIMATE}`.
- Produces: `PositionEngine.computeAll(TrackedLine line, List<LiveJourney> journeys, Instant now)` → `List<Vehicle>`.
- Produces: `PositionEngine.stopKey(String)` inchangé (utilisé par `NetworkRegistryBuilder` et `StationDepartureService`).

- [ ] **Step 1: Écrire les tests qui échouent**

Reconstruire `backend/src/test/java/com/mapidf/position/PositionEngineTest.java` autour des branches. Fixtures locales :

```java
package com.mapidf.position;

import java.time.Instant;
import java.util.List;

import com.mapidf.network.LineBranch;
import com.mapidf.network.TrackedLine;
import com.mapidf.rt.RtSnapshot.LiveJourney;
import com.mapidf.rt.RtSnapshot.LiveJourney.Call;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.PrecisionModel;
import static org.assertj.core.api.Assertions.assertThat;

class PositionEngineTest {

    private static final GeometryFactory GF = new GeometryFactory(new PrecisionModel(), 4326);
    private static final Instant NOW = Instant.parse("2026-07-22T14:00:00Z");
    private final PositionEngine engine = new PositionEngine();

    // Tronc P1(48.870) -> P2(48.850) -> P3(48.840), puis divergence :
    // branche "Villejuif" vers P4(2.300), branche "Ivry" vers P5(2.320).
    private static LineString shape(double lastLon) {
        return GF.createLineString(new Coordinate[]{
            new Coordinate(2.310, 48.870), new Coordinate(2.310, 48.850),
            new Coordinate(2.310, 48.840), new Coordinate(lastLon, 48.830)});
    }

    private static LineBranch branch(String shapeId, String terminus, String lastStopKey, double lastLon) {
        return LineBranch.of(shapeId, (short) 0, terminus, shape(lastLon), List.of(
            new StopOnLine("1", "Nord", 0.000, 0),
            new StopOnLine("2", "Correspondance", 0.020, 240),
            new StopOnLine("3", "Sud", 0.030, 480),
            new StopOnLine(lastStopKey, terminus, 0.045, 720)));
    }

    private static TrackedLine branchedLine() {
        return new TrackedLine("7", "IDFM:C01377", "STIF:Line::C01377:", "7", "#FF82B4", "METRO",
            List.of(branch("SH7A", "Villejuif", "4", 2.300),
                    branch("SH7B", "Ivry", "5", 2.320)));
    }

    private static LiveJourney journey(String destination, List<Call> calls) {
        return new LiveJourney("STIF:Line::C01377:", "J1", "0", destination, NOW.minusSeconds(30), calls);
    }

    @Test
    void picksTheBranchWhoseTerminusMatchesTheDestination() {
        // Arrêt commun au tronc : seule la destination permet de trancher. Sans ce choix, un
        // train d'Ivry serait placé sur la branche Villejuif, à ~1,5 km de sa position réelle.
        LiveJourney toIvry = journey("Ivry", List.of(
            new Call("STIF:StopPoint:Q:3:", NOW.plusSeconds(60), "ON_TIME"),
            new Call("STIF:StopPoint:Q:5:", NOW.plusSeconds(300), "ON_TIME")));

        List<Vehicle> vehicles = engine.computeAll(branchedLine(), List.of(toIvry), NOW);

        assertThat(vehicles).singleElement()
            .satisfies(v -> assertThat(v.headsign()).isEqualTo("Ivry"));
        // Le train est entre Sud et Ivry : sa longitude tend vers 2.320, pas vers 2.300.
        assertThat(vehicles.getFirst().lng()).isGreaterThan(2.310);
    }

    @Test
    void picksTheOnlyBranchThatServesTheNextStop() {
        // L'arrêt 4 n'existe que sur la branche Villejuif : aucun départage nécessaire.
        LiveJourney toVillejuif = journey("Inconnu", List.of(
            new Call("STIF:StopPoint:Q:4:", NOW.plusSeconds(120), "ON_TIME")));

        List<Vehicle> vehicles = engine.computeAll(branchedLine(), List.of(toVillejuif), NOW);

        assertThat(vehicles).singleElement()
            .satisfies(v -> assertThat(v.lng()).isLessThan(2.310));
    }

    @Test
    void dropsAJourneyWhoseNextStopIsOnNoBranch() {
        // Mesuré : 0,6 % du flux métro après couverture gloutonne — des StopPointRef SIRI
        // absents du GTFS. Doit être écarté proprement, pas lever.
        LiveJourney orphan = journey("Ailleurs", List.of(
            new Call("STIF:StopPoint:Q:999:", NOW.plusSeconds(60), "ON_TIME")));

        assertThat(engine.computeAll(branchedLine(), List.of(orphan), NOW)).isEmpty();
    }

    @Test
    void flagsASingleCallJourneyAsApproximate() {
        // Signal STRUCTUREL, jamais un seuil d'ETA : 36 % des courses métro n'ont qu'un appel
        // et sont bornées à l'arrêt précédant leur unique appel, souvent un terminus lointain.
        LiveJourney single = journey("Ivry", List.of(
            new Call("STIF:StopPoint:Q:5:", NOW.plusSeconds(900), "ON_TIME")));

        assertThat(engine.computeAll(branchedLine(), List.of(single), NOW))
            .singleElement()
            .extracting(Vehicle::confidence).isEqualTo(Vehicle.Confidence.APPROXIMATE);
    }

    @Test
    void marksAMultiCallJourneyAsReliable() {
        LiveJourney multi = journey("Ivry", List.of(
            new Call("STIF:StopPoint:Q:3:", NOW.plusSeconds(60), "ON_TIME"),
            new Call("STIF:StopPoint:Q:5:", NOW.plusSeconds(300), "ON_TIME")));

        assertThat(engine.computeAll(branchedLine(), List.of(multi), NOW))
            .singleElement()
            .extracting(Vehicle::confidence).isEqualTo(Vehicle.Confidence.RELIABLE);
    }

    @Test
    void carriesTheLineIdAndTheRecordedAtOfTheJourney() {
        LiveJourney multi = journey("Ivry", List.of(
            new Call("STIF:StopPoint:Q:3:", NOW.plusSeconds(60), "ON_TIME"),
            new Call("STIF:StopPoint:Q:5:", NOW.plusSeconds(300), "ON_TIME")));

        assertThat(engine.computeAll(branchedLine(), List.of(multi), NOW)).singleElement()
            .satisfies(v -> {
                assertThat(v.lineId()).isEqualTo("7");
                assertThat(v.journeyRef()).isEqualTo("J1");
                assertThat(v.recordedAt()).isEqualTo(NOW.minusSeconds(30));
            });
    }

    @Test
    void picksTheEarliestUpcomingCallEvenWhenCallsAreUnordered() {
        // Les EstimatedCall ne sont PAS triés et n'ont pas de champ Order (vérifié sur le flux
        // réel) : le prochain arrêt est le plus tôt À VENIR, pas le premier du tableau.
        LiveJourney unordered = journey("Ivry", List.of(
            new Call("STIF:StopPoint:Q:5:", NOW.plusSeconds(600), "ON_TIME"),
            new Call("STIF:StopPoint:Q:3:", NOW.plusSeconds(60), "ON_TIME"),
            new Call("STIF:StopPoint:Q:2:", NOW.minusSeconds(120), "ON_TIME")));

        assertThat(engine.computeAll(branchedLine(), List.of(unordered), NOW))
            .singleElement()
            .extracting(Vehicle::nextStop).isEqualTo("Sud");
    }

    @Test
    void extractsTheLastDigitGroupAsStopKey() {
        assertThat(PositionEngine.stopKey("STIF:StopPoint:Q:463221:")).isEqualTo("463221");
        assertThat(PositionEngine.stopKey("IDFM:463221")).isEqualTo("463221");
        assertThat(PositionEngine.stopKey("IDFM:StopPoint:59:463221")).isEqualTo("463221");
        assertThat(PositionEngine.stopKey(null)).isEmpty();
    }
}
```

- [ ] **Step 2: Lancer les tests pour vérifier qu'ils échouent**

Run: `cd backend && ./mvnw test -Dtest=PositionEngineTest`
Expected: FAIL — `computeAll(TrackedLine, List, Instant)` introuvable

- [ ] **Step 3: Étendre `Vehicle`**

```java
package com.mapidf.position;

import java.time.Instant;

/**
 * Un véhicule placé sur la carte.
 *
 * @param recordedAt  dernière mise à jour de la course côté SIRI, affichée telle quelle
 * @param confidence  fiabilité du PLACEMENT, sur un signal structurel : une course à un seul
 *                    appel est bornée à l'arrêt précédant celui-ci (36 % du flux métro mesuré).
 *                    Aucune ETA n'intervient — un train perturbé ne doit jamais être masqué.
 */
public record Vehicle(String journeyRef, String lineId, double lat, double lng, double bearing,
                      String status, String headsign, String nextStop, Instant expectedTime,
                      Instant recordedAt, Source source, Confidence confidence) {

    public enum Source {
        REALTIME, INTERPOLATED
    }

    public enum Confidence {
        RELIABLE, APPROXIMATE
    }
}
```

- [ ] **Step 4: Réécrire `PositionEngine`**

Remplacer `computeAll`, `compute`, `vehicleAt` et `pickDirection` par (les helpers `bearing`, `clamp`, `stopKey` et `terminusMatches` sont conservés tels quels ; `indexOfStop` est supprimé, remplacé par `LineBranch.indexOf`) :

```java
    private Counter unplaced;

    @Autowired
    public void attachMetrics(MeterRegistry registry) {
        this.unplaced = registry.counter("mapidf.position.unplaced");
    }

    public List<Vehicle> computeAll(TrackedLine line, List<RtSnapshot.LiveJourney> journeys, Instant now) {
        List<Vehicle> out = new ArrayList<>();
        for (RtSnapshot.LiveJourney journey : journeys) {
            Vehicle vehicle = compute(line, journey, now);
            if (vehicle != null) {
                out.add(vehicle);
            } else if (unplaced != null) {
                // Mesuré : 0,6 % du flux métro après couverture gloutonne. La dégradation reste
                // mesurable au lieu d'être silencieuse.
                unplaced.increment();
            }
        }
        return out;
    }

    private Vehicle compute(TrackedLine line, RtSnapshot.LiveJourney journey, Instant now) {
        List<RtSnapshot.LiveJourney.Call> sorted = journey.calls().stream()
            .sorted(Comparator.comparing(RtSnapshot.LiveJourney.Call::time))
            .toList();
        // Arrêt imminent = premier encore à venir ; s'il n'y en a plus, le dernier connu.
        // On n'exclut jamais un train qui a des données.
        RtSnapshot.LiveJourney.Call next = sorted.stream()
            .filter(call -> !call.time().isBefore(now))
            .findFirst()
            .orElse(sorted.getLast());
        RtSnapshot.LiveJourney.Call prev = sorted.stream()
            .filter(call -> call.time().isBefore(now))
            .reduce((a, b) -> b)
            .orElse(null);

        String nextKey = stopKey(next.stopRef());
        LineBranch branch = pickBranch(line, nextKey, journey.destination());
        if (branch == null) {
            return null;
        }
        List<StopOnLine> stops = branch.stops();
        int nextIdx = branch.indexOf(nextKey);
        StopOnLine to = stops.get(nextIdx);
        Vehicle.Confidence confidence = journey.calls().size() == 1
            ? Vehicle.Confidence.APPROXIMATE
            : Vehicle.Confidence.RELIABLE;

        if (nextIdx == 0) {
            // Prochain arrêt = tête de branche → placé à l'origine.
            StopOnLine after = stops.size() > 1 ? stops.get(1) : to;
            return vehicleAt(line, branch, journey, to, next, confidence,
                to.distanceAlongLine(), to.distanceAlongLine(), after.distanceAlongLine());
        }

        // Origine du segment : le dernier arrêt SIRI passé s'il est en amont sur cette branche
        // (interpolation aux VRAIES heures → capte le temps à quai) ; sinon l'arrêt précédent
        // du tracé, dont on estime l'heure de départ via l'horaire théorique.
        int prevIdx = prev == null ? -1 : branch.indexOf(stopKey(prev.stopRef()));
        double fromDist;
        Instant fromTime;
        if (prev != null && prevIdx >= 0 && prevIdx < nextIdx) {
            fromDist = stops.get(prevIdx).distanceAlongLine();
            fromTime = prev.time();
        } else {
            StopOnLine routePrev = stops.get(nextIdx - 1);
            int segmentSec = to.scheduledSec() - routePrev.scheduledSec();
            fromDist = routePrev.distanceAlongLine();
            fromTime = next.time().minusSeconds(Math.max(1, segmentSec));
        }

        long total = Duration.between(fromTime, next.time()).getSeconds();
        double fraction = total > 0
            ? clamp((double) Duration.between(fromTime, now).getSeconds() / total, 0.0, 1.0)
            : 1.0;
        double distance = fromDist + fraction * (to.distanceAlongLine() - fromDist);
        return vehicleAt(line, branch, journey, to, next, confidence,
            distance, fromDist, to.distanceAlongLine());
    }

    private Vehicle vehicleAt(TrackedLine line, LineBranch branch, RtSnapshot.LiveJourney journey,
                              StopOnLine next, RtSnapshot.LiveJourney.Call call,
                              Vehicle.Confidence confidence,
                              double distance, double fromDist, double toDist) {
        Coordinate point = branch.indexed().extractPoint(distance);
        return new Vehicle(journey.journeyRef(), line.id(), point.y, point.x,
            bearing(branch.indexed(), fromDist, toDist), call.departureStatus(),
            journey.destination(), next.stopName(), call.time(), journey.recordedAt(),
            Vehicle.Source.INTERPOLATED, confidence);
    }

    /**
     * Généralisation directe de l'ancien {@code pickDirection} : les candidates sont les
     * branches contenant l'arrêt imminent (lookup O(1)), départagées par correspondance
     * terminus / destination.
     */
    private LineBranch pickBranch(TrackedLine line, String nextStopKey, String destination) {
        List<LineBranch> candidates = line.branches().stream()
            .filter(branch -> branch.indexOf(nextStopKey) >= 0)
            .toList();
        if (candidates.isEmpty()) {
            return null;
        }
        if (candidates.size() == 1) {
            return candidates.getFirst();
        }
        return candidates.stream()
            .filter(branch -> terminusMatches(branch.terminusName(), destination))
            .findFirst()
            .orElse(candidates.getFirst());
    }
```

Adapter la signature de `bearing` pour prendre le `LengthIndexedLine` de la branche (inchangée sur le fond). Imports à ajouter : `com.mapidf.network.LineBranch`, `com.mapidf.network.TrackedLine`, `io.micrometer.core.instrument.Counter`, `io.micrometer.core.instrument.MeterRegistry`, `org.springframework.beans.factory.annotation.Autowired`.

- [ ] **Step 5: Lancer les tests puis la suite complète**

Run: `cd backend && ./mvnw test -Dtest=PositionEngineTest`
Expected: PASS (8 tests)

Run: `cd backend && ./mvnw verify`
Expected: BUILD SUCCESS

- [ ] **Step 6: Commit**

```bash
cd /home/abodet/workspace/perso/MapIDF
git add backend/src/main/java/com/mapidf/position backend/src/test/java/com/mapidf/position
git commit -m "feat(back): choix de branche et indicateur de confiance

Le moteur reçoit une ligne et choisit la branche avant d'interpoler, sur
la géométrie de cette branche. pickDirection faisait déjà le bon travail
(filtrer les candidats contenant l'arrêt, départager par terminus) : on
généralise des sens d'une ligne aux branches d'une ligne. Sans ce choix,
un train d'Ivry serait placé sur la branche Villejuif, à 1,5 km de sa
position réelle.

Vehicle porte désormais lineId, recordedAt et un indicateur de confiance.
Le signal est structurel : une course à un seul EstimatedCall est bornée à
l'arrêt précédant son unique appel, souvent un terminus lointain — 254
courses sur 705 mesurées, soit 36 %. Aucune ETA n'entre dans ce calcul,
conformément à la décision de ne jamais masquer un train perturbé.

Renomme tripId en journeyRef : le champ portait un journeyRef depuis
toujours. Compte les trains non plaçables dans une métrique (0,6 % mesuré
après couverture gloutonne) pour que la dégradation reste visible."
```

---

## Task 10: `GET /network`

Un seul appel au démarrage du front, servi **entièrement depuis le registry** — aucune requête SQL. Garder `/lines/{id}/shape` imposerait 16 appels et une déduplication des correspondances côté client (République remonterait dans 5 payloads).

Volume mesuré : 37 polylignes pour 8 110 points au total (médiane 229, max 335) et 321 stations.

**Files:**
- Create: `backend/src/main/java/com/mapidf/controllers/network/NetworkResponse.java`
- Create: `backend/src/main/java/com/mapidf/controllers/network/NetworkController.java`
- Create: `backend/src/test/java/com/mapidf/controllers/network/NetworkControllerIT.java`

**Interfaces:**
- Consumes: `LineRegistry.current()` (tâche 6), `GtfsStaticService.publishFromDatabase()` (tâche 7).
- Produces: `NetworkResponse` record — `List<LineDto> lines, List<ShapeDto> shapes, List<StationDto> stations` ; `LineDto(String id, String shortName, String color, String mode)` ; `ShapeDto(String lineId, short direction, String terminusName, double[][] coordinates)` ; `StationDto(String id, String name, double lat, double lng, List<String> lineIds)`.

- [ ] **Step 1: Écrire l'IT qui échoue**

`backend/src/test/java/com/mapidf/controllers/network/NetworkControllerIT.java` :

```java
package com.mapidf.controllers.network;

import com.mapidf.MapIdfTest;
import com.mapidf.gtfs.GtfsStaticLoader;
import com.mapidf.gtfs.GtfsStaticService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;

@MapIdfTest
class NetworkControllerIT {

    @Autowired WebApplicationContext wac;
    @Autowired GtfsStaticLoader loader;
    @Autowired GtfsStaticService staticService;
    MockMvc mockMvc;

    @BeforeEach
    void setup() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).build();
        try (var in = getClass().getResourceAsStream("/gtfs-branch.zip")) {
            loader.load(in);
        }
        staticService.publishFromDatabase();
    }

    @Test
    void returnsTheTrackedLines() throws Exception {
        mockMvc.perform(get("/network"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.lines", hasSize(2)))
            .andExpect(jsonPath("$.lines[*].id", containsInAnyOrder("7", "9")))
            .andExpect(jsonPath("$.lines[?(@.id == '7')].color").value("#FF82B4"))
            .andExpect(jsonPath("$.lines[?(@.id == '7')].mode").value("METRO"));
    }

    @Test
    void returnsOnePolylinePerBranch() throws Exception {
        // 4 branches : SH9 + SH9R pour la 9, SH7A + SH7B pour la 7.
        mockMvc.perform(get("/network"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.shapes", hasSize(4)))
            .andExpect(jsonPath("$.shapes[?(@.terminusName == 'Villejuif')].coordinates", hasSize(1)));
    }

    @Test
    void returnsStationsDeduplicatedWithTheirLines() throws Exception {
        mockMvc.perform(get("/network"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.stations", hasSize(7)))
            .andExpect(jsonPath("$.stations[?(@.id == 'STC')].name").value("Correspondance"))
            .andExpect(jsonPath("$.stations[?(@.id == 'STC')].lineIds[*]",
                containsInAnyOrder("7", "9")));
    }

    @Test
    void allowsBrowserCachingOfTheStaticNetwork() throws Exception {
        // Le réseau ne change qu'au rechargement GTFS (une fois par jour).
        mockMvc.perform(get("/network"))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "max-age=600, public"));
    }
}
```

- [ ] **Step 2: Lancer l'IT pour vérifier qu'il échoue**

Run: `cd backend && ./mvnw verify -Dit.test=NetworkControllerIT`
Expected: FAIL — 404 sur `/network`

- [ ] **Step 3: Créer `NetworkResponse`**

```java
package com.mapidf.controllers.network;

import java.util.List;

/**
 * Tout le réseau statique en un appel : 37 polylignes (8 110 points) et 321 stations
 * dédoublonnées sur le métro réel. Servi depuis le registry, sans requête SQL.
 */
public record NetworkResponse(List<LineDto> lines, List<ShapeDto> shapes, List<StationDto> stations) {

    public record LineDto(String id, String shortName, String color, String mode) {
    }

    /** Une polyligne par branche : le front en fait une feature GeoJSON coloriée par sa ligne. */
    public record ShapeDto(String lineId, short direction, String terminusName, double[][] coordinates) {
    }

    /** Une station physique et les lignes qui la desservent (61 correspondances sur 321). */
    public record StationDto(String id, String name, double lat, double lng, List<String> lineIds) {
    }
}
```

- [ ] **Step 4: Créer `NetworkController`**

```java
package com.mapidf.controllers.network;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.mapidf.controllers.network.NetworkResponse.LineDto;
import com.mapidf.controllers.network.NetworkResponse.ShapeDto;
import com.mapidf.controllers.network.NetworkResponse.StationDto;
import com.mapidf.network.LineBranch;
import com.mapidf.network.LineRegistry;
import com.mapidf.network.NetworkSnapshot;
import com.mapidf.network.TrackedLine;
import lombok.AllArgsConstructor;
import org.locationtech.jts.geom.Coordinate;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@AllArgsConstructor
public class NetworkController {

    private final LineRegistry registry;

    @GetMapping("/network")
    public ResponseEntity<NetworkResponse> network() {
        NetworkSnapshot snapshot = registry.current();

        List<LineDto> lines = snapshot.lines().stream()
            .map(line -> new LineDto(line.id(), line.shortName(), line.color(), line.mode()))
            .toList();

        List<ShapeDto> shapes = new ArrayList<>();
        for (TrackedLine line : snapshot.lines()) {
            for (LineBranch branch : line.branches()) {
                shapes.add(new ShapeDto(line.id(), branch.direction(), branch.terminusName(),
                    toCoordinates(branch)));
            }
        }

        List<StationDto> stations = snapshot.stations().stream()
            .map(station -> new StationDto(station.id(), station.name(),
                station.lat(), station.lng(), station.lineIds()))
            .toList();

        // Statique entre deux rechargements GTFS (un par jour) : on laisse le navigateur
        // cacher plutôt que de resérialiser 8 110 points à chaque onglet ouvert.
        return ResponseEntity.ok()
            .cacheControl(CacheControl.maxAge(Duration.ofMinutes(10)).cachePublic())
            .body(new NetworkResponse(lines, shapes, stations));
    }

    private static double[][] toCoordinates(LineBranch branch) {
        Coordinate[] source = branch.geom().getCoordinates();
        double[][] out = new double[source.length][];
        for (int i = 0; i < source.length; i++) {
            out[i] = new double[]{source[i].x, source[i].y};
        }
        return out;
    }
}
```

- [ ] **Step 5: Lancer l'IT puis la suite complète**

Run: `cd backend && ./mvnw verify -Dit.test=NetworkControllerIT`
Expected: PASS (4 tests)

Run: `cd backend && ./mvnw verify`
Expected: BUILD SUCCESS

- [ ] **Step 6: Commit**

```bash
cd /home/abodet/workspace/perso/MapIDF
git add backend/src/main/java/com/mapidf/controllers/network \
        backend/src/test/java/com/mapidf/controllers/network
git commit -m "feat(back): GET /network, tout le réseau statique en un appel

Lignes, une polyligne par branche, et stations dédoublonnées côté serveur
avec les lignes qui les desservent. Servi entièrement depuis le registry,
sans requête SQL, et cacheable 10 min.

Remplace /lines/{id}/shape : avec 16 lignes, celui-ci imposerait 16 appels
au démarrage et une déduplication des correspondances côté client
(République remonterait dans 5 payloads). Volume mesuré : 37 polylignes
pour 8 110 points et 321 stations."
```

---

## Task 11: `GET /vehicles`

Un seul poll toutes les 4 s pour tout le réseau, au lieu de 16. Réponse ~140 Ko pour 705 véhicules, sous 20 Ko avec la compression activée en tâche 1.

**Files:**
- Create: `backend/src/main/java/com/mapidf/controllers/vehicles/VehiclesResponse.java`
- Create: `backend/src/main/java/com/mapidf/controllers/vehicles/VehiclesController.java`
- Create: `backend/src/test/java/com/mapidf/controllers/vehicles/VehiclesControllerIT.java`

**Interfaces:**
- Consumes: `LineRegistry.current()`, `PositionEngine.computeAll(TrackedLine, List, Instant)` (tâche 9), `RealtimePoller.current()`.
- Produces: `VehiclesResponse(Instant asOf, List<VehicleDto> vehicles)` ; `VehicleDto(String journeyRef, String lineId, double lat, double lng, double bearing, String status, String headsign, String nextStop, Instant expectedTime, Instant recordedAt, String source, String confidence)`.

- [ ] **Step 1: Écrire l'IT qui échoue**

`backend/src/test/java/com/mapidf/controllers/vehicles/VehiclesControllerIT.java` :

```java
package com.mapidf.controllers.vehicles;

import com.mapidf.MapIdfTest;
import com.mapidf.gtfs.GtfsStaticLoader;
import com.mapidf.gtfs.GtfsStaticService;
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
class VehiclesControllerIT {

    @Autowired WebApplicationContext wac;
    @Autowired GtfsStaticLoader loader;
    @Autowired GtfsStaticService staticService;
    MockMvc mockMvc;

    @BeforeEach
    void setup() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).build();
        try (var in = getClass().getResourceAsStream("/gtfs-branch.zip")) {
            loader.load(in);
        }
        staticService.publishFromDatabase();
    }

    @Test
    void returnsAnEnvelopeCoveringTheWholeTrackedNetwork() throws Exception {
        // Le poller n'a rien ingéré en profil test (realtime-base-url vide), donc la liste est
        // vide — mais l'endpoint doit répondre 200 avec une enveloppe complète et un asOf frais,
        // et surtout NE PAS lever alors que le registry contient deux lignes et quatre branches.
        String asOf = mockMvc.perform(get("/vehicles"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.vehicles").isArray())
            .andExpect(jsonPath("$.vehicles", hasSize(0)))
            .andReturn().getResponse().getContentAsString();

        assertThat(asOf).contains("\"asOf\"");
    }

    @Test
    void placesTheJourneysOfEveryTrackedLine() throws Exception {
        // Sans donnée temps réel, /vehicles est structurellement incapable de renvoyer un
        // véhicule : on injecte donc un snapshot couvrant les DEUX lignes de la fixture, pour
        // vérifier que le contrôleur balaie bien tout le réseau et non une seule ligne.
        poller.pollOnce(url -> new java.io.ByteArrayInputStream(
            TWO_LINE_SNAPSHOT.getBytes(java.nio.charset.StandardCharsets.UTF_8)), Instant.now());

        mockMvc.perform(get("/vehicles"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.vehicles[*].lineId", containsInAnyOrder("7", "9")))
            .andExpect(jsonPath("$.vehicles[*].confidence",
                containsInAnyOrder("APPROXIMATE", "APPROXIMATE")));
    }
}
```

`pollOnce(Fetcher, Instant)` est visible depuis le paquet `com.mapidf.rt`. Comme l'IT vit dans `com.mapidf.controllers.vehicles`, **élargir sa visibilité à `public`** dans `RealtimePoller` avec le commentaire suivant, plutôt que de déplacer l'IT :

```java
    // public (et non package-private) pour permettre aux IT de contrôleur d'injecter un
    // snapshot déterministe sans appeler PRIM : le poll réel passe par poll(), planifié.
    public void pollOnce(Fetcher fetcher, Instant asOf) {
```

La constante du snapshot, dans l'IT — arrêt `Q:2:` pour la 9 (station STC) et `Q:4:` pour la 7 (Villejuif, propre à la branche SH7A), une seule course chacune donc `APPROXIMATE` des deux côtés :

```java
    private static final String TWO_LINE_SNAPSHOT = """
        {"Siri":{"ServiceDelivery":{"ResponseTimestamp":"2026-07-29T08:00:00.000Z",
          "EstimatedTimetableDelivery":[{"EstimatedJourneyVersionFrame":[{
            "EstimatedVehicleJourney":[
              {"RecordedAtTime":"2026-07-29T08:00:00.000Z",
               "LineRef":{"value":"STIF:Line::C01379:"},
               "DirectionRef":{"value":"0"},
               "DatedVehicleJourneyRef":{"value":"V9"},
               "DestinationName":[{"value":"Gamma"}],
               "EstimatedCalls":{"EstimatedCall":[{
                 "StopPointRef":{"value":"STIF:StopPoint:Q:3:"},
                 "ExpectedDepartureTime":"2026-07-29T09:00:00.000Z",
                 "DepartureStatus":"ON_TIME"}]}},
              {"RecordedAtTime":"2026-07-29T08:00:00.000Z",
               "LineRef":{"value":"STIF:Line::C01377:"},
               "DirectionRef":{"value":"0"},
               "DatedVehicleJourneyRef":{"value":"V7"},
               "DestinationName":[{"value":"Villejuif"}],
               "EstimatedCalls":{"EstimatedCall":[{
                 "StopPointRef":{"value":"STIF:StopPoint:Q:4:"},
                 "ExpectedDepartureTime":"2026-07-29T09:00:00.000Z",
                 "DepartureStatus":"ON_TIME"}]}}
            ]}]}]
        }}}
        """;
```

**Le snapshot du poller est un singleton qui survit au rollback transactionnel**, exactement comme le registry, et l'ordre d'exécution JUnit n'est pas garanti. Chaque test pose donc son propre snapshot dans le `@BeforeEach`, ce qui rend les deux déterministes quel que soit l'ordre :

```java
    @Autowired RealtimePoller poller;

    @BeforeEach
    void setup() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).build();
        try (var in = getClass().getResourceAsStream("/gtfs-branch.zip")) {
            loader.load(in);
        }
        staticService.publishFromDatabase();
        // Snapshot vide déterministe : "{}" est un JSON valide sans EstimatedVehicleJourney,
        // donc 0 course — sans dépendre du test précédent ni d'un appel PRIM. Un corps
        // réellement vide serait risqué : si le parse levait, pollOnce conserverait par
        // conception le snapshot précédent et le test deviendrait dépendant de l'ordre.
        poller.pollOnce(url -> new java.io.ByteArrayInputStream(
            "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8)), Instant.now());
    }
```

Le second test appelle ensuite `pollOnce` avec `TWO_LINE_SNAPSHOT` pour écraser ce vide.

- [ ] **Step 2: Lancer l'IT pour vérifier qu'il échoue**

Run: `cd backend && ./mvnw verify -Dit.test=VehiclesControllerIT`
Expected: FAIL — 404 sur `/vehicles`

- [ ] **Step 3: Créer `VehiclesResponse`**

```java
package com.mapidf.controllers.vehicles;

import java.time.Instant;
import java.util.List;

import com.mapidf.position.Vehicle;

public record VehiclesResponse(Instant asOf, List<VehicleDto> vehicles) {

    /**
     * @param recordedAt  dernière mise à jour de la course côté SIRI (information d'affichage)
     * @param confidence  RELIABLE ou APPROXIMATE — fiabilité du placement, sur un signal
     *                    structurel. Le front atténue les APPROXIMATE sans jamais les masquer.
     */
    public record VehicleDto(String journeyRef, String lineId, double lat, double lng,
                             double bearing, String status, String headsign, String nextStop,
                             Instant expectedTime, Instant recordedAt,
                             String source, String confidence) {

        public static VehicleDto from(Vehicle vehicle) {
            return new VehicleDto(vehicle.journeyRef(), vehicle.lineId(), vehicle.lat(),
                vehicle.lng(), vehicle.bearing(), vehicle.status(), vehicle.headsign(),
                vehicle.nextStop(), vehicle.expectedTime(), vehicle.recordedAt(),
                vehicle.source().name(), vehicle.confidence().name());
        }
    }
}
```

- [ ] **Step 4: Créer `VehiclesController`**

```java
package com.mapidf.controllers.vehicles;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.mapidf.controllers.vehicles.VehiclesResponse.VehicleDto;
import com.mapidf.network.LineRegistry;
import com.mapidf.network.TrackedLine;
import com.mapidf.position.PositionEngine;
import com.mapidf.position.Vehicle;
import com.mapidf.rt.RealtimePoller;
import com.mapidf.rt.RtSnapshot;
import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tous les véhicules du réseau suivi en un appel : le front fait UN poll toutes les 4 s, pas
 * seize. Aucune requête SQL — registry en mémoire et snapshot temps réel.
 *
 * <p>Les positions dépendent de l'instant, donc elles sont recalculées à chaque requête ; ce qui
 * est immuable (géométries indexées, arrêts projetés) est préconstruit dans le registry.
 */
@RestController
@AllArgsConstructor
public class VehiclesController {

    private final LineRegistry registry;
    private final PositionEngine positionEngine;
    private final RealtimePoller poller;

    @GetMapping("/vehicles")
    public VehiclesResponse vehicles() {
        Instant now = Instant.now();
        RtSnapshot snapshot = poller.current();
        List<VehicleDto> vehicles = new ArrayList<>();
        for (TrackedLine line : registry.current().lines()) {
            for (Vehicle vehicle : positionEngine.computeAll(
                    line, snapshot.forLine(line.siriLineRef()), now)) {
                vehicles.add(VehicleDto.from(vehicle));
            }
        }
        return new VehiclesResponse(now, vehicles);
    }
}
```

- [ ] **Step 5: Lancer l'IT puis la suite complète**

Run: `cd backend && ./mvnw verify -Dit.test=VehiclesControllerIT`
Expected: PASS

Run: `cd backend && ./mvnw verify`
Expected: BUILD SUCCESS

- [ ] **Step 6: Commit**

```bash
cd /home/abodet/workspace/perso/MapIDF
git add backend/src/main/java/com/mapidf/controllers/vehicles \
        backend/src/test/java/com/mapidf/controllers/vehicles
git commit -m "feat(back): GET /vehicles, tout le réseau en un seul poll

Le front fait un appel toutes les 4 s au lieu de seize. Chaque véhicule
porte son lineId, sa confiance de placement et le recordedAt de sa course.

Aucune requête SQL : registry en mémoire et snapshot temps réel. Les
positions dépendent de l'instant donc sont recalculées par requête, mais
géométries indexées et arrêts projetés sont préconstruits — /vehicles
rebâtissait un LengthIndexedLine à chaque appel.

Réponse ~140 Ko pour 705 véhicules, sous 20 Ko avec la compression."
```

---

## Task 12: `GET /stations/{id}/departures` groupé par ligne

Mesuré : **61 stations sur 321 sont des correspondances** — 45 à 2 lignes, 11 à 3, 3 à 4, et 2 à 5 (République, Châtelet). Sur une correspondance on veut les passages de toutes les lignes, d'où un endpoint au niveau station et non plus au niveau ligne.

La **fusion des deux sens est conservée** : la station résout tous ses quais, donc les deux sens, regroupés par destination. L'imbrication `ligne → direction` corrige au passage une collision latente entre deux lignes partageant un nom de destination. Et sur une ligne à branches, une station du tronc affiche légitimement plus de deux groupes.

**Files:**
- Create: `backend/src/main/java/com/mapidf/controllers/stations/DeparturesResponse.java`
- Create: `backend/src/main/java/com/mapidf/controllers/stations/StationsController.java`
- Modify: `backend/src/main/java/com/mapidf/services/StationDepartureService.java`
- Modify: `backend/src/test/java/com/mapidf/services/StationDepartureServiceTest.java`
- Create: `backend/src/test/java/com/mapidf/controllers/stations/StationsControllerIT.java`

**Interfaces:**
- Consumes: `LineRegistry.requireStation(String)`, `LineRegistry.current()` (tâche 6), `RtSnapshot` (tâche 8), `PositionEngine.stopKey(String)`.
- Produces: `DeparturesResponse(String stationName, List<LineDepartures> lines)` ; `LineDepartures(String lineId, String shortName, String color, List<Direction> directions)` ; `Direction(String destination, List<Passage> passages)` ; `Passage(String journeyRef, Instant expectedTime, String status)`.
- Produces: `StationDepartureService.departures(Station station, List<TrackedLine> lines, RtSnapshot snapshot, Instant now, int perDirection)` → `DeparturesResponse`.

- [ ] **Step 1: Écrire les tests qui échouent**

Remplacer `StationDepartureServiceTest.java` par :

```java
package com.mapidf.services;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.mapidf.controllers.stations.DeparturesResponse;
import com.mapidf.network.Station;
import com.mapidf.network.TrackedLine;
import com.mapidf.rt.RtSnapshot;
import com.mapidf.rt.RtSnapshot.LiveJourney;
import com.mapidf.rt.RtSnapshot.LiveJourney.Call;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class StationDepartureServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-22T14:00:00Z");
    private static final String NINE = "STIF:Line::C01379:";
    private static final String SEVEN = "STIF:Line::C01377:";

    private final StationDepartureService service = new StationDepartureService();

    private static TrackedLine line(String id, String siriRef, String color) {
        return new TrackedLine(id, "IDFM:X" + id, siriRef, id, color, "METRO", List.of());
    }

    private static LiveJourney journey(String siriRef, String ref, String destination,
                                      String stopRef, Instant time, String status) {
        return new LiveJourney(siriRef, ref, "0", destination, NOW,
            List.of(new Call(stopRef, time, status)));
    }

    /** Station de correspondance : quai S2 pour la 9, quai P2 pour la 7. */
    private static Station correspondence() {
        return new Station("STC", "Correspondance", 48.850, 2.310,
            List.of("S2", "P2"), List.of("7", "9"));
    }

    @Test
    void groupsPassagesByLineThenDirection() {
        RtSnapshot snapshot = new RtSnapshot(NOW, Map.of(
            NINE, List.of(journey(NINE, "J9", "Gamma", "STIF:StopPoint:Q:2:",
                NOW.plusSeconds(120), "ON_TIME")),
            SEVEN, List.of(journey(SEVEN, "J7", "Ivry", "STIF:StopPoint:Q:2:",
                NOW.plusSeconds(180), "ON_TIME"))));

        DeparturesResponse response = service.departures(correspondence(),
            List.of(line("7", SEVEN, "#FF82B4"), line("9", NINE, "#D2D200")), snapshot, NOW, 3);

        assertThat(response.stationName()).isEqualTo("Correspondance");
        assertThat(response.lines()).extracting(DeparturesResponse.LineDepartures::lineId)
            .containsExactly("7", "9");
        assertThat(response.lines().getFirst().color()).isEqualTo("#FF82B4");
        assertThat(response.lines().getFirst().directions())
            .singleElement()
            .extracting(DeparturesResponse.Direction::destination).isEqualTo("Ivry");
    }

    @Test
    void keepsBothDirectionsOfTheSameLine() {
        // La fusion des deux sens à une station est le comportement attendu : la station résout
        // tous ses quais, donc les deux sens, chacun devenant un groupe de destination.
        RtSnapshot snapshot = new RtSnapshot(NOW, Map.of(NINE, List.of(
            journey(NINE, "J1", "Gamma", "STIF:StopPoint:Q:2:", NOW.plusSeconds(60), "ON_TIME"),
            journey(NINE, "J2", "Alpha", "STIF:StopPoint:Q:2:", NOW.plusSeconds(90), "ON_TIME"))));

        DeparturesResponse response = service.departures(correspondence(),
            List.of(line("9", NINE, "#D2D200")), snapshot, NOW, 3);

        assertThat(response.lines()).singleElement()
            .extracting(DeparturesResponse.LineDepartures::directions).asInstanceOf(
                org.assertj.core.api.InstanceOfAssertFactories.list(DeparturesResponse.Direction.class))
            .extracting(DeparturesResponse.Direction::destination)
            .containsExactlyInAnyOrder("Gamma", "Alpha");
    }

    @Test
    void ordersLinesByNumberNotAlphabetically() {
        // Ordre humain attendu : 3, 3b, 7, 9, 14 — pas 14, 3, 3b, 7, 9.
        RtSnapshot snapshot = new RtSnapshot(NOW, Map.of());

        DeparturesResponse response = service.departures(
            new Station("ST", "Multi", 0, 0, List.of("S2"),
                List.of("14", "3", "3b", "7", "9")),
            List.of(line("14", "L14", "#640082"), line("3", "L3", "#6E6E00"),
                    line("3b", "L3B", "#82C8E6"), line("7", "L7", "#FF82B4"),
                    line("9", "L9", "#D2D200")),
            snapshot, NOW, 3);

        assertThat(response.lines()).extracting(DeparturesResponse.LineDepartures::lineId)
            .containsExactly("3", "3b", "7", "9", "14");
    }

    @Test
    void ignoresPassagesAlreadyGoneAndStopsOfOtherStations() {
        RtSnapshot snapshot = new RtSnapshot(NOW, Map.of(NINE, List.of(
            journey(NINE, "PAST", "Gamma", "STIF:StopPoint:Q:2:", NOW.minusSeconds(60), "ON_TIME"),
            journey(NINE, "ELSEWHERE", "Gamma", "STIF:StopPoint:Q:99:", NOW.plusSeconds(60), "ON_TIME"))));

        DeparturesResponse response = service.departures(correspondence(),
            List.of(line("9", NINE, "#D2D200")), snapshot, NOW, 3);

        assertThat(response.lines()).isEmpty();
    }

    @Test
    void limitsThePassagesPerDirectionAndSortsThemByTime() {
        RtSnapshot snapshot = new RtSnapshot(NOW, Map.of(NINE, List.of(
            journey(NINE, "C", "Gamma", "STIF:StopPoint:Q:2:", NOW.plusSeconds(300), "ON_TIME"),
            journey(NINE, "A", "Gamma", "STIF:StopPoint:Q:2:", NOW.plusSeconds(60), "ON_TIME"),
            journey(NINE, "B", "Gamma", "STIF:StopPoint:Q:2:", NOW.plusSeconds(180), "ON_TIME"),
            journey(NINE, "D", "Gamma", "STIF:StopPoint:Q:2:", NOW.plusSeconds(600), "ON_TIME"))));

        DeparturesResponse response = service.departures(correspondence(),
            List.of(line("9", NINE, "#D2D200")), snapshot, NOW, 3);

        assertThat(response.lines().getFirst().directions().getFirst().passages())
            .extracting(DeparturesResponse.Passage::journeyRef)
            .containsExactly("A", "B", "C");
    }

    @Test
    void carriesTheDelayedStatusThrough() {
        // Mesuré sur une ligne 8 perturbée : 14 % de ses appels en DELAYED, le taux le plus
        // élevé du réseau. Le statut doit remonter pour que le front l'affiche enfin.
        RtSnapshot snapshot = new RtSnapshot(NOW, Map.of(NINE, List.of(
            journey(NINE, "J1", "Gamma", "STIF:StopPoint:Q:2:", NOW.plusSeconds(60), "DELAYED"))));

        DeparturesResponse response = service.departures(correspondence(),
            List.of(line("9", NINE, "#D2D200")), snapshot, NOW, 3);

        assertThat(response.lines().getFirst().directions().getFirst().passages())
            .singleElement()
            .extracting(DeparturesResponse.Passage::status).isEqualTo("DELAYED");
    }
}
```

- [ ] **Step 2: Lancer les tests pour vérifier qu'ils échouent**

Run: `cd backend && ./mvnw test -Dtest=StationDepartureServiceTest`
Expected: FAIL — `cannot find symbol: class DeparturesResponse` dans `controllers.stations`

- [ ] **Step 3: Créer `DeparturesResponse`**

```java
package com.mapidf.controllers.stations;

import java.time.Instant;
import java.util.List;

/**
 * Prochains passages à une station, groupés par ligne puis par direction.
 *
 * <p>Mesuré : 61 stations sur 321 sont des correspondances, jusqu'à 5 lignes. Grouper par
 * destination seule à travers plusieurs lignes fusionnerait deux lignes partageant un nom de
 * destination — d'où le niveau « ligne ».
 *
 * <p>Sur une ligne à branches, une station du tronc commun affiche plus de deux directions
 * (la 13 à Saint-Lazare montre Asnières et Saint-Denis séparément) : c'est le comportement juste.
 */
public record DeparturesResponse(String stationName, List<LineDepartures> lines) {

    public record LineDepartures(String lineId, String shortName, String color,
                                 List<Direction> directions) {
    }

    public record Direction(String destination, List<Passage> passages) {
    }

    public record Passage(String journeyRef, Instant expectedTime, String status) {
    }
}
```

- [ ] **Step 4: Réécrire `StationDepartureService`**

```java
package com.mapidf.services;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.mapidf.controllers.stations.DeparturesResponse;
import com.mapidf.controllers.stations.DeparturesResponse.Direction;
import com.mapidf.controllers.stations.DeparturesResponse.LineDepartures;
import com.mapidf.controllers.stations.DeparturesResponse.Passage;
import com.mapidf.network.Station;
import com.mapidf.network.TrackedLine;
import com.mapidf.position.PositionEngine;
import com.mapidf.rt.RtSnapshot;
import com.mapidf.rt.RtSnapshot.LiveJourney;
import org.springframework.stereotype.Service;

/**
 * Prochains passages à une station, agrégés depuis le snapshot temps réel déjà en mémoire
 * (aucun appel PRIM, aucune requête SQL). Un passage = un appel futur d'une course dont l'arrêt
 * appartient à la station.
 */
@Service
public class StationDepartureService {

    private static final Pattern LEADING_DIGITS = Pattern.compile("^(\\d+)");

    public DeparturesResponse departures(Station station, List<TrackedLine> lines,
                                         RtSnapshot snapshot, Instant now, int perDirection) {
        Set<String> stopKeys = station.platformIds().stream()
            .map(PositionEngine::stopKey)
            .collect(Collectors.toSet());

        List<LineDepartures> byLine = new ArrayList<>();
        for (TrackedLine line : sortedByHumanOrder(lines)) {
            List<Direction> directions = directionsOf(
                snapshot.forLine(line.siriLineRef()), stopKeys, now, perDirection);
            if (!directions.isEmpty()) {
                byLine.add(new LineDepartures(line.id(), line.shortName(), line.color(), directions));
            }
        }
        return new DeparturesResponse(station.name(), byLine);
    }

    private List<Direction> directionsOf(List<LiveJourney> journeys, Set<String> stopKeys,
                                         Instant now, int perDirection) {
        Map<String, List<Passage>> byDestination = new LinkedHashMap<>();
        for (LiveJourney journey : journeys) {
            for (LiveJourney.Call call : journey.calls()) {
                if (call.time() == null || call.time().isBefore(now)) {
                    continue;
                }
                if (!stopKeys.contains(PositionEngine.stopKey(call.stopRef()))) {
                    continue;
                }
                byDestination.computeIfAbsent(journey.destination(), key -> new ArrayList<>())
                    .add(new Passage(journey.journeyRef(), call.time(), call.departureStatus()));
            }
        }
        return byDestination.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .map(entry -> new Direction(entry.getKey(), entry.getValue().stream()
                .sorted(Comparator.comparing(Passage::expectedTime))
                .limit(perDirection)
                .toList()))
            .toList();
    }

    /**
     * Ordre humain : 3, 3b, 7, 9, 14 — pas l'ordre alphabétique qui donnerait 14 avant 3.
     * Stable entre deux rafraîchissements : trier par passage le plus imminent réordonnerait
     * le panneau sous le curseur toutes les 4 s.
     */
    private static List<TrackedLine> sortedByHumanOrder(List<TrackedLine> lines) {
        return lines.stream()
            .sorted(Comparator.comparingInt(StationDepartureService::leadingNumber)
                .thenComparing(TrackedLine::id))
            .toList();
    }

    private static int leadingNumber(TrackedLine line) {
        Matcher matcher = LEADING_DIGITS.matcher(line.id());
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : Integer.MAX_VALUE;
    }
}
```

- [ ] **Step 5: Créer `StationsController`**

```java
package com.mapidf.controllers.stations;

import java.time.Instant;
import java.util.List;

import com.mapidf.network.LineRegistry;
import com.mapidf.network.Station;
import com.mapidf.network.TrackedLine;
import com.mapidf.rt.RealtimePoller;
import com.mapidf.services.StationDepartureService;
import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
@AllArgsConstructor
public class StationsController {

    private static final int PASSAGES_PER_DIRECTION = 3;

    private final LineRegistry registry;
    private final RealtimePoller poller;
    private final StationDepartureService departureService;

    @GetMapping("/stations/{id}/departures")
    public DeparturesResponse departures(@PathVariable String id) {
        Station station = registry.requireStation(id);
        // Seules les lignes qui desservent cette station : jusqu'à 5 sur une correspondance.
        List<TrackedLine> lines = station.lineIds().stream()
            .map(lineId -> registry.current().linesById().get(lineId))
            .filter(java.util.Objects::nonNull)
            .toList();
        return departureService.departures(
            station, lines, poller.current(), Instant.now(), PASSAGES_PER_DIRECTION);
    }
}
```

- [ ] **Step 6: Écrire l'IT du contrôleur**

`backend/src/test/java/com/mapidf/controllers/stations/StationsControllerIT.java` :

```java
package com.mapidf.controllers.stations;

import com.mapidf.MapIdfTest;
import com.mapidf.gtfs.GtfsStaticLoader;
import com.mapidf.gtfs.GtfsStaticService;
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
class StationsControllerIT {

    @Autowired WebApplicationContext wac;
    @Autowired GtfsStaticLoader loader;
    @Autowired GtfsStaticService staticService;
    MockMvc mockMvc;

    @BeforeEach
    void setup() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).build();
        try (var in = getClass().getResourceAsStream("/gtfs-branch.zip")) {
            loader.load(in);
        }
        staticService.publishFromDatabase();
    }

    @Test
    void returnsTheStationEnvelopeForACorrespondence() throws Exception {
        mockMvc.perform(get("/stations/STC/departures"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.stationName").value("Correspondance"))
            .andExpect(jsonPath("$.lines").isArray());
    }

    @Test
    void returnsNotFoundForAnUnknownStation() throws Exception {
        mockMvc.perform(get("/stations/NOPE/departures"))
            .andExpect(status().isNotFound());
    }
}
```

- [ ] **Step 7: Lancer les tests puis la suite complète**

Run: `cd backend && ./mvnw test -Dtest=StationDepartureServiceTest`
Expected: PASS (6 tests)

Run: `cd backend && ./mvnw verify`
Expected: BUILD SUCCESS — l'API est reconstruite en entier, l'état intermédiaire de la tâche 4 est refermé.

- [ ] **Step 8: Commit**

```bash
cd /home/abodet/workspace/perso/MapIDF
git add -A backend/src
git commit -m "feat(back): GET /stations/{id}/departures groupé par ligne

Passe d'un endpoint au niveau ligne à un endpoint au niveau station :
mesuré, 61 stations sur 321 sont des correspondances (45 à deux lignes, 11
à trois, 3 à quatre, 2 à cinq — République et Châtelet). Sur une
correspondance on veut les passages de toutes les lignes.

La fusion des deux sens est conservée : la station résout tous ses quais,
chaque sens devenant un groupe de destination. L'imbrication ligne ->
direction corrige au passage une collision latente entre deux lignes
partageant un nom de destination. Sur une ligne à branches, une station du
tronc affiche légitimement plus de deux directions.

Lignes ordonnées par numéro (3, 3b, 7, 9, 14) et non alphabétiquement, et
ordre stable : trier par passage le plus imminent réordonnerait le panneau
sous le curseur toutes les 4 s.

Referme l'état intermédiaire de la bascule de schéma : l'API est complète."
```

---

## Task 13: Front — `/network` et couches réseau

Le front n'a pas de tests unitaires (convention du projet) : la vérification est `npm run build` plus un contrôle visuel décrit à chaque étape.

Deux sources GeoJSON couvrent tout le réseau, donc le nombre de lignes n'ajoute plus de couches. Volume : 37 polylignes pour 8 110 points, 321 stations.

**Files:**
- Modify: `frontend/src/api/types.ts`
- Create: `frontend/src/api/network.ts`
- Modify: `frontend/src/api/config.ts`
- Create: `frontend/src/ui/color.ts`
- Create: `frontend/src/map/useNetwork.ts`
- Modify: `frontend/src/App.tsx`
- Delete: `frontend/src/api/lines.ts`
- Delete: `frontend/src/map/useLineShape.ts`

**Interfaces:**
- Consumes: `GET /network`, `GET /vehicles`, `GET /stations/{id}/departures` (tâches 10-12).
- Produces: `NetworkResponse`, `Vehicle`, `VehiclesResponse`, `DeparturesResponse` dans `api/types.ts`.
- Produces: `fetchNetwork()`, `fetchVehicles()`, `fetchDepartures(stationId, signal?)` dans `api/network.ts`.
- Produces: `lightenForTrack(hex: string, keep?: number): string` dans `ui/color.ts`.
- Produces: `useNetwork(map: MlMap | null): NetworkResponse | null`.

- [ ] **Step 1: Réécrire les types**

Remplacer `frontend/src/api/types.ts` par :

```ts
export interface NetworkLine {
  id: string;
  shortName: string;
  color: string;
  mode: string;
}

export interface NetworkShape {
  lineId: string;
  direction: number;
  terminusName: string;
  coordinates: [number, number][];
}

export interface NetworkStation {
  id: string;
  name: string;
  lat: number;
  lng: number;
  lineIds: string[];
}

export interface NetworkResponse {
  lines: NetworkLine[];
  shapes: NetworkShape[];
  stations: NetworkStation[];
}

export interface Vehicle {
  journeyRef: string;
  lineId: string;
  lat: number;
  lng: number;
  bearing: number;
  status: string;
  headsign: string;
  nextStop: string;
  expectedTime: string;
  /** Dernière mise à jour de la course côté SIRI. Information, pas critère d'atténuation. */
  recordedAt: string | null;
  source: "REALTIME" | "INTERPOLATED";
  /** APPROXIMATE = course à un seul appel SIRI (36 % du flux) : atténué, jamais masqué. */
  confidence: "RELIABLE" | "APPROXIMATE";
}

export interface VehiclesResponse {
  asOf: string;
  vehicles: Vehicle[];
}

export interface Passage {
  journeyRef: string;
  expectedTime: string;
  status: string;
}

export interface DeparturesResponse {
  stationName: string;
  lines: {
    lineId: string;
    shortName: string;
    color: string;
    directions: { destination: string; passages: Passage[] }[];
  }[];
}
```

- [ ] **Step 2: Créer le client d'API et nettoyer la configuration**

`frontend/src/api/network.ts` :

```ts
import { API_BASE } from "./config";
import type { NetworkResponse, VehiclesResponse, DeparturesResponse } from "./types";

async function getJson<T>(path: string, signal?: AbortSignal): Promise<T> {
  const response = await fetch(`${API_BASE}${path}`, { signal });
  if (!response.ok) {
    throw new Error(`${path} ${response.status}`);
  }
  return response.json();
}

/** Tout le réseau statique en un appel (37 polylignes, 321 stations), cacheable 10 min. */
export function fetchNetwork(): Promise<NetworkResponse> {
  return getJson<NetworkResponse>("/network");
}

/** Tous les véhicules du réseau suivi : un seul poll toutes les 4 s, pas un par ligne. */
export function fetchVehicles(): Promise<VehiclesResponse> {
  return getJson<VehiclesResponse>("/vehicles");
}

export function fetchDepartures(stationId: string, signal?: AbortSignal): Promise<DeparturesResponse> {
  return getJson<DeparturesResponse>(`/stations/${encodeURIComponent(stationId)}/departures`, signal);
}
```

Puis `rm frontend/src/api/lines.ts` et, dans `frontend/src/api/config.ts`, supprimer la ligne `export const LINE_ID = "9";` (l'id n'était qu'un libellé d'URL ignoré par le backend).

- [ ] **Step 3: Créer l'éclaircissement de couleur**

`frontend/src/ui/color.ts` :

```ts
/**
 * Éclaircit une couleur de ligne pour le tracé de fond.
 *
 * Reproduit le rendu de l'ancien `line-opacity: 0.45` sur fond clair, mais de façon
 * **idempotente sous superposition** : deux branches d'une même ligne partagent leur tronc
 * (~15 km sur 21 pour la 7), donc celui-ci est dessiné deux fois exactement superposé. Avec
 * une opacité de 0,45, l'opacité résultante monterait à ~0,70 et le tronc commun de la 7, de
 * la 13 et de la 10 apparaîtrait visiblement plus foncé que le reste du réseau.
 */
export function lightenForTrack(hex: string, keep = 0.45): string {
  const value = hex.replace("#", "");
  const full = value.length === 3 ? value.split("").map((c) => c + c).join("") : value;
  const channel = (offset: number) => {
    const raw = Number.parseInt(full.slice(offset, offset + 2), 16);
    const base = Number.isNaN(raw) ? 0 : raw;
    return Math.round(base * keep + 255 * (1 - keep));
  };
  return `rgb(${channel(0)}, ${channel(2)}, ${channel(4)})`;
}
```

- [ ] **Step 4: Créer `useNetwork`**

`frontend/src/map/useNetwork.ts` :

```ts
import { useEffect, useState } from "react";
import type { Map as MlMap } from "maplibre-gl";
import { fetchNetwork } from "../api/network";
import { lightenForTrack } from "../ui/color";
import { whenStyleReady } from "./mapReady";
import type { NetworkResponse } from "../api/types";

/**
 * Charge le réseau en un appel et pose DEUX sources pour tout le réseau : `line-shapes`
 * (une feature par branche, coloriée par sa propriété) et `stops` (stations dédoublonnées
 * côté serveur). Le nombre de lignes n'ajoute donc aucune couche.
 */
export function useNetwork(map: MlMap | null): NetworkResponse | null {
  const [network, setNetwork] = useState<NetworkResponse | null>(null);

  useEffect(() => {
    if (!map) {
      return;
    }
    let cancelled = false;
    let cancelReady: (() => void) | null = null;
    let cleanupCursors: (() => void) | null = null;

    fetchNetwork().then((data) => {
      if (cancelled) {
        return;
      }
      setNetwork(data);
      const colorByLine = new Map(data.lines.map((line) => [line.id, line.color]));

      const draw = () => {
        if (cancelled || map.getSource("line-shapes")) {
          return;
        }
        map.addSource("line-shapes", {
          type: "geojson",
          data: {
            type: "FeatureCollection",
            features: data.shapes.map((shape) => ({
              type: "Feature",
              properties: {
                lineId: shape.lineId,
                trackColor: lightenForTrack(colorByLine.get(shape.lineId) ?? "#000000"),
              },
              geometry: { type: "LineString", coordinates: shape.coordinates },
            })),
          },
        });
        // Opacité pleine sur une couleur éclaircie : voir lightenForTrack. Une seule couche
        // pour les 37 branches, coloriée par feature.
        map.addLayer({
          id: "line-shapes",
          type: "line",
          source: "line-shapes",
          paint: { "line-color": ["get", "trackColor"], "line-width": 4, "line-opacity": 1 },
        });

        map.addSource("stops", {
          type: "geojson",
          data: {
            type: "FeatureCollection",
            features: data.stations.map((station) => ({
              type: "Feature",
              properties: {
                id: station.id,
                name: station.name,
                // Une correspondance dessert plusieurs lignes : on prend la première pour
                // l'anneau. Le panneau, lui, montre bien toutes ses lignes.
                color: colorByLine.get(station.lineIds[0]) ?? "#666666",
              },
              geometry: { type: "Point", coordinates: [station.lng, station.lat] },
            })),
          },
        });
        map.addLayer({
          id: "stops",
          type: "circle",
          source: "stops",
          minzoom: 11,
          paint: {
            "circle-radius": 5,
            "circle-color": "#fff",
            "circle-stroke-color": ["get", "color"],
            "circle-stroke-width": 2,
          },
        });
        // Noms seulement en zoom rapproché (collision gérée par MapLibre) : coût maîtrisé
        // même avec 321 stations.
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
          paint: { "text-color": "#111", "text-halo-color": "#fff", "text-halo-width": 1.5 },
        });
        map.addLayer({
          id: "stops-selected",
          type: "circle",
          source: "stops",
          minzoom: 11,
          filter: ["==", ["get", "id"], "__none__"],
          paint: {
            "circle-radius": 10,
            "circle-color": "rgba(29,78,216,0.15)",
            "circle-stroke-color": "#1d4ed8",
            "circle-stroke-width": 3,
          },
        });

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
      };
      cancelReady = whenStyleReady(map, draw);
    });

    return () => {
      cancelled = true;
      cancelReady?.();
      cleanupCursors?.();
    };
  }, [map]);

  return network;
}
```

Puis `rm frontend/src/map/useLineShape.ts`.

- [ ] **Step 5: Adapter `App.tsx` au minimum**

Dans `App.tsx` : remplacer les imports `useLineShape`, `fetchDepartures` depuis `./api/lines` et `LINE_ID` par `useNetwork` et `fetchDepartures` depuis `./api/network` ; remplacer `useLineShape(map, LINE_ID, setLineColor)` par `const network = useNetwork(map);` et supprimer l'état `lineColor` ; adapter les appels `fetchDepartures(LINE_ID, id, signal)` en `fetchDepartures(id, signal)` ; adapter `highlightedTripIds` qui parcourait `station.directions` en parcourant `station.lines.flatMap((l) => l.directions)` ; et adapter l'appel à `useVehicles` (dont la signature change en tâche 14) — pour cette étape, passer `useVehicles(map, network, selectedJourneyRef, follow, onSelected, setCounts, highlightedJourneyRefs)`.

`Legend` reste affichée avec un compteur total le temps de la tâche 15 : `<Legend color="#666" count={count} />`.

- [ ] **Step 6: Vérifier le build**

Run: `cd frontend && npm run build`
Expected: build réussi, aucune erreur TypeScript.

- [ ] **Step 7: Contrôle visuel**

Demander à l'utilisateur de recharger le front (il gère le démarrage). Attendu : les 16 lignes tracées avec leur couleur, les stations en ronds à partir du zoom 11, les noms à partir du zoom 12. **Vérifier spécifiquement le tronc commun de la ligne 7** (entre Louis Blanc et Maison Blanche) : il ne doit pas apparaître plus foncé que le reste de la ligne.

- [ ] **Step 8: Commit**

```bash
cd /home/abodet/workspace/perso/MapIDF
git add -A frontend/src
git commit -m "feat(front): réseau complet en un appel, deux couches pour 16 lignes

useNetwork remplace useLineShape : un appel à /network pose une source
line-shapes (37 branches coloriées par feature) et une source stops (321
stations dédoublonnées côté serveur). Le nombre de lignes n'ajoute plus de
couches.

Les tracés passent en opacité pleine sur une couleur éclaircie plutôt
qu'en line-opacity 0.45 : deux branches d'une même ligne partagent leur
tronc (~15 km sur 21 pour la 7) et le dessinent donc deux fois superposé,
ce qui porterait l'opacité résultante à ~0,70 et ferait ressortir le tronc
commun de la 7, de la 13 et de la 10.

Supprime LINE_ID, qui n'était qu'un libellé d'URL ignoré du backend."
```

---

## Task 14: Front — véhicules multi-lignes et allègement de la boucle

~705 véhicules interpolés au lieu de ~50. `render()` construit aujourd'hui des features neuves à chaque frame avec 7 propriétés dont 3 chaînes : à 15 fps et 705 véhicules, ~10 600 objets par seconde.

L'icône SDF avec `icon-color` est écartée : SDF est monochrome, on perdrait le liseré blanc qui rend les flèches lisibles sur le fond de carte. À la place, une image par couleur distincte — **14 seulement**, la 13 et la 3bis partageant `#82C8E6`, la 6 et la 7bis `#82DC73`.

**Files:**
- Modify: `frontend/src/map/VehicleLayer.ts`
- Modify: `frontend/src/map/useVehicles.ts`

**Interfaces:**
- Consumes: `NetworkResponse`, `Vehicle` (tâche 13), `GET /vehicles` (tâche 11).
- Produces: `new VehicleLayer(map, durationMs, colorByLine: Map<string, string>)` ; méthodes `update(vehicles: Vehicle[], now: number)`, `setSelected(journeyRef: string | null)`, `setFollow(boolean)`, `setHighlighted(Set<string>)`, `setVisibleLines(lineIds: Set<string> | null)`, `destroy()`.
- Produces: `useVehicles(map, network, selectedJourneyRef, follow, onSelected, onCounts, highlightedJourneyRefs, visibleLines)` ; `onCounts` reçoit un `Map<string, number>` (véhicules par ligne).

- [ ] **Step 1: Une image de flèche par couleur**

Dans `VehicleLayer.ts`, remplacer le champ `color` du constructeur par `colorByLine: Map<string, string>`, supprimer `setColor` et `updateImage`, et ajouter :

```ts
  /** Identifiant d'image MapLibre pour une couleur donnée (14 couleurs distinctes au métro). */
  private iconIdFor(color: string): string {
    const id = `vehicle-arrow-${color.replace("#", "")}`;
    if (!this.map.hasImage(id)) {
      this.map.addImage(id, this.arrowImage(color));
    }
    return id;
  }

  private arrowImage(color: string): ImageData {
    const size = 24;
    const canvas = document.createElement("canvas");
    canvas.width = size;
    canvas.height = size;
    const ctx = canvas.getContext("2d")!;
    ctx.fillStyle = color;
    ctx.strokeStyle = "#fff";
    ctx.lineWidth = 2;
    ctx.beginPath();
    ctx.moveTo(size / 2, 2);
    ctx.lineTo(size - 4, size - 4);
    ctx.lineTo(4, size - 4);
    ctx.closePath();
    ctx.fill();
    ctx.stroke();
    return ctx.getImageData(0, 0, size, size);
  }
```

Dans `ensureLayer`, la couche `vehicles` devient :

```ts
      map.addSource("vehicles", {
        type: "geojson",
        promoteId: "journeyRef",
        data: this.featureCollection([]),
      });
      // ... couches vehicles-halo et vehicles-highlight inchangées ...
      this.map.addLayer({
        id: "vehicles",
        type: "symbol",
        source: "vehicles",
        layout: {
          "icon-image": ["get", "icon"],
          "icon-rotate": ["get", "bearing"],
          "icon-rotation-alignment": "map",
          "icon-allow-overlap": true,
          "icon-size": ["interpolate", ["linear"], ["zoom"], 10, 0.5, 13, 0.85, 16, 1.5],
        },
        paint: {
          // APPROXIMATE = course à un seul appel SIRI (36 % du flux mesuré) : le train est
          // borné à l'arrêt précédant son unique appel, souvent un terminus lointain. Atténué,
          // jamais masqué — un train perturbé doit rester visible.
          "icon-opacity": ["case", ["get", "approximate"], 0.45, 1],
        },
      });
```

- [ ] **Step 2: Alléger la boucle de rendu**

Remplacer l'interface `Anim` et `render()` par :

```ts
interface Anim {
  from: [number, number];
  to: [number, number];
  bearing: number;
  start: number;
  vehicle: Vehicle;
  /** Feature réutilisée d'une frame à l'autre : on ne mute que ses coordonnées. */
  feature: GeoJSON.Feature<GeoJSON.Point>;
}
```

Dans `update(...)`, à la création d'une anim, construire la feature **une fois** :

```ts
      const icon = this.iconIdFor(this.colorByLine.get(vehicle.lineId) ?? "#666666");
      const feature: GeoJSON.Feature<GeoJSON.Point> = {
        type: "Feature",
        // Seules les propriétés qui servent au RENDU. headsign, nextStop, expectedTime,
        // status et recordedAt ne servent qu'au clic : useVehicles les garde dans une Map,
        // ce qui évite de recopier trois chaînes par véhicule et par frame (705 × 15/s).
        properties: {
          journeyRef: vehicle.journeyRef,
          lineId: vehicle.lineId,
          bearing: vehicle.bearing,
          icon,
          approximate: vehicle.confidence === "APPROXIMATE",
        },
        geometry: { type: "Point", coordinates: [vehicle.lng, vehicle.lat] },
      };
```

Et réutiliser la feature existante quand l'anim existe déjà, en mettant à jour ses propriétés variables (`bearing`, `icon`, `approximate`).

`render()` devient :

```ts
  private render(now: number) {
    const source = this.map.getSource("vehicles") as GeoJSONSource | undefined;
    if (!source) {
      return;
    }
    // Culling : on n'envoie que les véhicules du viewport élargi (marge 20 %). Les anims
    // restent maintenues pour tous → le tween survit à une sortie/entrée d'écran.
    const bounds = this.map.getBounds();
    const west = bounds.getWest();
    const east = bounds.getEast();
    const south = bounds.getSouth();
    const north = bounds.getNorth();
    const padX = (east - west) * 0.2;
    const padY = (north - south) * 0.2;

    let followPoint: [number, number] | null = null;
    // Tableau réutilisé : à 705 véhicules et 15 fps, réallouer une liste et 705 objets par
    // frame génère une pression GC inutile.
    this.rendered.length = 0;
    for (const anim of this.anims.values()) {
      const [lng, lat] = this.pointAt(anim, now);
      if (anim.vehicle.journeyRef === this.selectedJourneyRef && this.follow) {
        followPoint = [lng, lat];
      }
      if (this.visibleLines && !this.visibleLines.has(anim.vehicle.lineId)) {
        continue;
      }
      if (lng < west - padX || lng > east + padX || lat < south - padY || lat > north + padY) {
        continue;
      }
      anim.feature.geometry.coordinates[0] = lng;
      anim.feature.geometry.coordinates[1] = lat;
      this.rendered.push(anim.feature);
    }
    source.setData({ type: "FeatureCollection", features: this.rendered });
    if (followPoint) {
      this.map.jumpTo({ center: followPoint });
    }
  }
```

Ajouter les champs `private rendered: GeoJSON.Feature[] = [];`, `private visibleLines: Set<string> | null = null;` et la méthode :

```ts
  /** Filtre client par ligne : aucun appel réseau, on cesse simplement d'émettre les features. */
  setVisibleLines(lineIds: Set<string> | null) {
    this.visibleLines = lineIds;
    this.render(performance.now());
  }
```

Renommer partout `selectedTripId` en `selectedJourneyRef` et `vehicle.tripId` en `vehicle.journeyRef`. Dans `destroy()`, remplacer la suppression de l'unique image par une boucle sur les images `vehicle-arrow-*` créées (garder leurs identifiants dans un `Set<string>`).

- [ ] **Step 3: Adapter `useVehicles`**

Signature et corps :

```ts
export function useVehicles(
  map: MlMap | null,
  network: NetworkResponse | null,
  selectedJourneyRef: string | null = null,
  follow = false,
  onSelected?: (vehicle: Vehicle | null) => void,
  onCounts?: (counts: Map<string, number>) => void,
  highlightedJourneyRefs: Set<string> = new Set(),
  visibleLines: Set<string> | null = null,
) {
```

La couche n'est créée qu'une fois le réseau connu (il fournit les couleurs) : l'effet dépend de `[map, network]` et sort si `network` est `null`. Le poll appelle `fetchVehicles()` sans identifiant de ligne. Remplacer `lastVehiclesRef` par une `Map<string, Vehicle>` indexée par `journeyRef` — c'est elle qui alimente les panneaux, puisque les propriétés correspondantes ne sont plus dans la source :

```ts
        const byRef = new Map(response.vehicles.map((v) => [v.journeyRef, v]));
        vehiclesByRef.current = byRef;
        layer.update(response.vehicles, performance.now());
        const counts = new Map<string, number>();
        for (const vehicle of response.vehicles) {
          counts.set(vehicle.lineId, (counts.get(vehicle.lineId) ?? 0) + 1);
        }
        onCountsRef.current?.(counts);
        const ref = selectedRef.current;
        if (ref) {
          onSelectedRef.current?.(byRef.get(ref) ?? null);
        }
```

Ajouter un effet `useEffect(() => { layerRef.current?.setVisibleLines(visibleLines); }, [map, network, visibleLines]);` et remplacer l'effet `setColor` par rien (les couleurs viennent du réseau).

- [ ] **Step 4: Vérifier le build**

Run: `cd frontend && npm run build`
Expected: build réussi.

- [ ] **Step 5: Contrôle visuel**

Attendu : les trains apparaissent à la couleur de leur ligne ; une partie d'entre eux (~36 %) est visiblement atténuée ; le clic ouvre toujours la carte de détail avec le bon prochain arrêt.

- [ ] **Step 6: Commit**

```bash
cd /home/abodet/workspace/perso/MapIDF
git add -A frontend/src
git commit -m "feat(front): véhicules colorés par ligne et boucle de rendu allégée

Une image de flèche par couleur distincte (14 au métro : la 13 et la 3bis
partagent #82C8E6, la 6 et la 7bis #82DC73) avec icon-image piloté par
feature. L'icône SDF et icon-color sont écartées : SDF est monochrome, on
perdrait le liseré blanc qui rend les flèches lisibles.

Les courses APPROXIMATE passent en opacité réduite — 36 % du flux mesuré,
bornées à l'arrêt précédant leur unique appel SIRI. Atténuées, jamais
masquées.

Allège la boucle, qui passe de ~50 à ~705 véhicules : les propriétés qui ne
servent qu'au clic (headsign, nextStop, expectedTime, status, recordedAt)
sortent de la source pour une Map côté hook, et les objets features sont
réutilisés d'une frame à l'autre au lieu d'être réalloués 705 fois par
frame à 15 fps."
```

---

## Task 15: Front — sélecteur de lignes, panneaux groupés, badge de retard

Le filtre est **purement client**, sans appel réseau. Pour les stations, une expression MapLibre sur un tableau `lineIds` est malcommode : on recalcule la `FeatureCollection` (321 features, trivial) et on appelle `setData`. Une station reste visible si au moins une de ses lignes est active.

Le badge de retard est l'ajout le plus rentable du lot : `DepartureStatus` est transmis depuis toujours et n'a **jamais été affiché** (ticket MINOR ouvert). Mesuré sur une ligne 8 en perturbation, 14 % de ses appels étaient en `DELAYED`, le taux le plus élevé du réseau.

**Files:**
- Create: `frontend/src/ui/LinePicker.tsx`
- Modify: `frontend/src/ui/StopPanel.tsx`
- Modify: `frontend/src/ui/VehiclePanel.tsx`
- Modify: `frontend/src/map/useNetwork.ts`
- Modify: `frontend/src/App.tsx`
- Delete: `frontend/src/ui/Legend.tsx`

**Interfaces:**
- Consumes: `NetworkLine`, `Vehicle`, `DeparturesResponse` (tâche 13), `VehicleLayer.setVisibleLines` (tâche 14).
- Produces: `useNetwork(map: MlMap | null, visibleLines: Set<string> | null)` — **le hook gagne un second paramètre** ; il refait `setData` sur la source `stops` et `setFilter` sur `line-shapes` quand la sélection change.
- Produces: `LinePicker` avec les props `{ lines: NetworkLine[]; counts: Map<string, number>; visible: Set<string> | null; onToggle: (lineId: string) => void; onShowAll: () => void }`.
- Produces: `StopPanel` avec les props `{ data: DeparturesResponse | null; onClose: () => void; onSelectTrain?: (journeyRef: string) => void }`.
- Produces: `VehiclePanel` avec les props `{ vehicle: Vehicle | null; following?: boolean; onFollow?: () => void; onClose: () => void }`.

- [ ] **Step 1: Créer `LinePicker`**

`frontend/src/ui/LinePicker.tsx` :

```tsx
import type { NetworkLine } from "../api/types";

interface Props {
  lines: NetworkLine[];
  counts: Map<string, number>;
  /** null = toutes les lignes visibles. */
  visible: Set<string> | null;
  onToggle: (lineId: string) => void;
  onShowAll: () => void;
}

/** Ordre humain : 1, 2, 3, 3b, 4… 14 — et non l'ordre alphabétique, qui mettrait 14 avant 3. */
function humanOrder(a: NetworkLine, b: NetworkLine): number {
  const num = (id: string) => Number.parseInt(id, 10) || Number.MAX_SAFE_INTEGER;
  return num(a.id) - num(b.id) || a.id.localeCompare(b.id);
}

export function LinePicker({ lines, counts, visible, onToggle, onShowAll }: Props) {
  const total = [...counts.values()].reduce((sum, n) => sum + n, 0);
  const sorted = [...lines].sort(humanOrder);
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
        maxWidth: 300,
      }}
    >
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", gap: 8 }}>
        <b>{total} trains en circulation</b>
        {visible && (
          <button
            onClick={onShowAll}
            style={{ border: "none", background: "none", color: "#1d4ed8", cursor: "pointer", font: "inherit" }}
          >
            tout afficher
          </button>
        )}
      </div>
      <div style={{ display: "flex", flexWrap: "wrap", gap: 6, marginTop: 8 }}>
        {sorted.map((line) => {
          const shown = !visible || visible.has(line.id);
          return (
            <button
              key={line.id}
              onClick={() => onToggle(line.id)}
              title={`${counts.get(line.id) ?? 0} train(s) sur la ligne ${line.shortName}`}
              style={{
                display: "flex",
                alignItems: "center",
                gap: 4,
                padding: "2px 6px",
                border: "1px solid #ddd",
                borderRadius: 12,
                background: shown ? "#fff" : "#f3f3f3",
                opacity: shown ? 1 : 0.45,
                cursor: "pointer",
                font: "12px sans-serif",
              }}
            >
              <span
                style={{
                  width: 16,
                  height: 16,
                  borderRadius: "50%",
                  background: line.color,
                  color: "#fff",
                  font: "bold 10px sans-serif",
                  display: "flex",
                  alignItems: "center",
                  justifyContent: "center",
                }}
              >
                {line.shortName}
              </span>
              {counts.get(line.id) ?? 0}
            </button>
          );
        })}
      </div>
      <div style={{ color: "#666", marginTop: 6 }}>
        Position estimée (pas de GPS en métro). Les trains atténués ont un placement approximatif.
      </div>
    </div>
  );
}
```

Puis `rm frontend/src/ui/Legend.tsx`.

- [ ] **Step 2: Appliquer le filtre aux couches réseau**

Dans `useNetwork.ts`, ajouter le paramètre `visibleLines: Set<string> | null` et, après l'effet de chargement, un effet dédié :

```ts
  // Filtre client : aucun appel réseau. Les tracés se filtrent par expression ; les stations
  // demandent un recalcul de la collection, car une expression MapLibre sur un tableau
  // lineIds est malcommode — 321 features, c'est trivial.
  useEffect(() => {
    if (!map || !network || !map.getSource("stops")) {
      return;
    }
    const colorByLine = new Map(network.lines.map((line) => [line.id, line.color]));
    map.setFilter("line-shapes", visibleLines
      ? ["in", ["get", "lineId"], ["literal", [...visibleLines]]]
      : null);
    const stations = network.stations.filter(
      (station) => !visibleLines || station.lineIds.some((id) => visibleLines.has(id)));
    (map.getSource("stops") as GeoJSONSource).setData({
      type: "FeatureCollection",
      features: stations.map((station) => ({
        type: "Feature",
        properties: {
          id: station.id,
          name: station.name,
          color: colorByLine.get(
            station.lineIds.find((id) => !visibleLines || visibleLines.has(id)) ?? station.lineIds[0]
          ) ?? "#666666",
        },
        geometry: { type: "Point", coordinates: [station.lng, station.lat] },
      })),
    });
  }, [map, network, visibleLines]);
```

(importer `GeoJSONSource` depuis `maplibre-gl`.)

- [ ] **Step 3: Grouper `StopPanel` par ligne et afficher le retard**

Remplacer le corps de `StopPanel.tsx`. La logique de masquage des passages partis est conservée, appliquée par ligne puis par direction :

```tsx
import type { DeparturesResponse } from "../api/types";
import { formatEta } from "./formatEta";

interface Props {
  data: DeparturesResponse | null;
  onClose: () => void;
  onSelectTrain?: (journeyRef: string) => void;
}

/** DepartureStatus est transmis depuis toujours et n'était jamais affiché. */
function DelayBadge({ status }: { status: string }) {
  if (status?.toUpperCase() !== "DELAYED") {
    return null;
  }
  return (
    <span
      style={{
        marginLeft: 6,
        padding: "0 5px",
        borderRadius: 8,
        background: "#fde68a",
        color: "#92400e",
        font: "bold 11px sans-serif",
      }}
    >
      retardé
    </span>
  );
}

export function StopPanel({ data, onClose, onSelectTrain }: Props) {
  if (!data) {
    return null;
  }
  // Le panneau peut vieillir entre deux rafraîchissements : on masque les passages déjà partis
  // et les groupes qui n'ont plus rien à venir.
  const now = Date.now();
  const lines = data.lines
    .map((line) => ({
      ...line,
      directions: line.directions
        .map((dir) => ({
          ...dir,
          passages: dir.passages.filter((p) => new Date(p.expectedTime).getTime() > now),
        }))
        .filter((dir) => dir.passages.length > 0),
    }))
    .filter((line) => line.directions.length > 0);

  return (
    <div
      style={{
        position: "absolute",
        top: 12,
        right: 12,
        width: 280,
        maxHeight: "70vh",
        overflowY: "auto",
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
      {lines.length === 0 && (
        <p style={{ margin: "4px 0", color: "#666" }}>Aucun passage annoncé.</p>
      )}
      {lines.map((line) => (
        <div key={line.lineId} style={{ margin: "10px 0 0" }}>
          <div style={{ display: "flex", alignItems: "center", gap: 6 }}>
            <span
              style={{
                width: 18, height: 18, borderRadius: "50%", background: line.color, color: "#fff",
                font: "bold 11px sans-serif", display: "flex", alignItems: "center", justifyContent: "center",
              }}
            >
              {line.shortName}
            </span>
          </div>
          {line.directions.map((dir) => (
            <div key={dir.destination} style={{ margin: "4px 0 0 4px" }}>
              <p style={{ margin: "0 0 2px", fontWeight: 600 }}>→ {dir.destination}</p>
              <ul style={{ margin: "0 0 0 16px", padding: 0, listStyle: "none" }}>
                {dir.passages.map((p) => (
                  <li key={p.journeyRef}>
                    <button
                      onClick={() => onSelectTrain?.(p.journeyRef)}
                      style={{
                        border: "none", background: "none", padding: "2px 0", cursor: "pointer",
                        font: "inherit", color: "#1d4ed8", textAlign: "left", width: "100%",
                      }}
                      title="Suivre ce métro"
                    >
                      {formatEta(p.expectedTime)}
                      <DelayBadge status={p.status} />
                    </button>
                  </li>
                ))}
              </ul>
            </div>
          ))}
        </div>
      ))}
    </div>
  );
}
```

- [ ] **Step 4: Signaler le placement approximatif dans `VehiclePanel`**

Changer le type de la prop en `Vehicle | null` (au lieu de `VehicleSummary`), et ajouter après la ligne « Position » :

```tsx
      {vehicle.confidence === "APPROXIMATE" && (
        <p style={{ margin: "8px 0 0", padding: "6px 8px", background: "#fef3c7", borderRadius: 6, color: "#92400e" }}>
          Position approximative : le flux temps réel n'annonce qu'un seul arrêt pour ce train.
        </p>
      )}
      {vehicle.recordedAt && (
        <p style={{ margin: "4px 0", color: "#666" }}>
          Donnée du {new Date(vehicle.recordedAt).toLocaleTimeString("fr-FR")}
        </p>
      )}
```

- [ ] **Step 5: Câbler le filtre dans `App.tsx`**

Ajouter les états et remplacer `Legend` :

```tsx
  const [visibleLines, setVisibleLines] = useState<Set<string> | null>(null);
  const [counts, setCounts] = useState<Map<string, number>>(new Map());

  const network = useNetwork(map, visibleLines);

  const toggleLine = (lineId: string) => {
    setVisibleLines((current) => {
      // Premier clic depuis « toutes » : on isole la ligne cliquée, ce qui est l'intention la
      // plus fréquente sur 16 lignes.
      const all = new Set(network?.lines.map((line) => line.id) ?? []);
      const next = new Set(current ?? all);
      if (next.has(lineId)) {
        next.delete(lineId);
      } else {
        next.add(lineId);
      }
      return next.size === all.size ? null : next;
    });
  };
```

et dans le JSX :

```tsx
      <LinePicker
        lines={network?.lines ?? []}
        counts={counts}
        visible={visibleLines}
        onToggle={toggleLine}
        onShowAll={() => setVisibleLines(null)}
      />
```

Adapter `highlightedTripIds` (renommé `highlightedJourneyRefs`) :

```tsx
  const highlightedJourneyRefs = useMemo(
    () => new Set(
      station?.lines.flatMap((line) => line.directions.flatMap((d) => d.passages.map((p) => p.journeyRef))) ?? []),
    [station],
  );
```

et passer `visibleLines` à `useVehicles`.

- [ ] **Step 6: Vérifier le build**

Run: `cd frontend && npm run build`
Expected: build réussi, aucune erreur TypeScript.

- [ ] **Step 7: Contrôle visuel**

Attendu : le sélecteur liste les 16 lignes dans l'ordre 1…14 puis 3bis/7bis, avec leur compteur ; cliquer une pastille masque sa ligne (tracé, trains **et** stations qui ne servent qu'elle) sans appel réseau ; ouvrir **Châtelet ou République** montre les passages de toutes les lignes de la station, groupés par ligne puis par direction ; un passage `DELAYED` porte un badge « retardé » ; cliquer un train atténué affiche l'encart « Position approximative ».

- [ ] **Step 8: Commit**

```bash
cd /home/abodet/workspace/perso/MapIDF
git add -A frontend/src
git commit -m "feat(front): sélecteur de lignes, passages groupés et badge de retard

LinePicker remplace Legend : les 16 lignes avec leur pastille de couleur et
leur compteur, en ordre humain (1…14 puis 3bis, 7bis). Le filtre est
purement client — setFilter sur les tracés, recalcul des 321 stations pour
la source stops, une station restant visible si au moins une de ses lignes
est active.

StopPanel groupe par ligne puis par direction, ce que le multi-ligne rend
nécessaire : 61 stations sur 321 sont des correspondances, jusqu'à 5 lignes
à République et Châtelet.

Affiche enfin le retard : DepartureStatus était transmis depuis toujours et
jamais rendu (ticket MINOR ouvert). Mesuré sur une ligne 8 en perturbation,
14 % de ses appels étaient en DELAYED, le taux le plus élevé du réseau.

VehiclePanel signale un placement approximatif et l'heure de la donnée."
```

---

## Task 16: Mesure du temps de frame et mise à jour des docs

Les deux optimisations de la tâche 14 sont **raisonnées, pas mesurées**. La spec en fait la condition de fin du volet front : on relève, on décide, on documente.

**Files:**
- Modify: `backend/docs/prim-integration.md`
- Modify: `CLAUDE.md`
- Modify: `README.md` (si la section API y décrit les anciens endpoints)

- [ ] **Step 1: Relever le temps de frame**

Demander à l'utilisateur d'ouvrir le front (il gère le démarrage), de se placer à l'échelle de Paris (zoom ~11, tous les trains visibles, aucun filtre actif), puis dans l'onglet Performance des outils de développement d'enregistrer ~10 s et de relever :

- le temps moyen passé dans le rappel `requestAnimationFrame` par frame ;
- le nombre de frames longues (> 50 ms) sur les 10 s ;
- le compteur de véhicules affiché par le sélecteur (attendu ~705 en heure de service).

Consigner la mesure ici même, dans ce plan, sous l'étape.

- [ ] **Step 2: Décider en fonction du relevé**

- Temps de frame **< 16 ms** : rien à faire, le volet front est terminé.
- Entre **16 et 33 ms** : porter `RENDER_INTERVAL_MS` de 66 à 100 ms dans `VehicleLayer.ts` (10 fps ; sur un tween de 4 s cela laisse 40 pas pour un point qui avance lentement, donc visuellement indiscernable) et remesurer.
- **> 33 ms** : ne pas empiler les micro-optimisations. Profiler d'abord pour savoir si le coût est dans `setData` (sérialisation MapLibre) ou dans notre boucle, et ouvrir un ticket dédié avec le relevé — c'est un chantier de perf à part entière, pas une retouche.

- [ ] **Step 3: Corriger `backend/docs/prim-integration.md`**

Ajouter une section datée qui **corrige trois affirmations** de la doc actuelle et consigne les mesures :

```markdown
## Mise à jour 2026-07-29 — mesures sur le flux global et corrections

Relevé sur un snapshot réel de `estimated-timetable` (09h52) et sur le GTFS IDFM du jour.

### Volumétrie

| Mesure | Valeur |
|---|---|
| Flux global brut | 45,6 Mo JSON |
| **Avec `Accept-Encoding: gzip`** | **3,96 Mo** (×11,5), 5,8 s |
| Projection | ~4,7 Go/jour (vs ~55 Go sans gzip) |
| Courses, toutes lignes | 12 018 sur 1 013 lignes |
| **Courses métro** | **705** |
| Courses à un seul `EstimatedCall` | 254 / 705 = **36 %** |
| Courses dont la donnée dépasse 2 min | 110 / 705 = 16 % (médiane 0,4 min, max 16,8) |

**PRIM sert le flux global en gzip** — le `HttpClient` de Java ne le négocie pas seul.

### Corrections

- **`DatedVehicleJourneyRef` est renseigné sur les 705 courses métro.** La doc supposait
  l'inverse ; l'identité composite de secours ne sert donc jamais pour le métro, et l'identité
  des trains est stable entre deux polls.
- **`OriginRef` est présent comme clé mais vide (`{}`)** sur les 705 courses. Inexploitable,
  comme indiqué. Idem `RouteRef`, `OriginName`, `VehicleJourneyName`.
- **`RecordedAtTime` existe sur chaque course** et n'était pas exploité. C'est l'horodatage de
  dernière mise à jour. **Ce n'est pas un signal de perturbation** : mesuré pendant une
  perturbation de la ligne 8, celle-ci avait la donnée la plus fraîche du réseau (2 % de
  courses au-delà de 2 min, contre 73 % sur la 3bis). La perturbation se lit dans
  `DepartureStatus: DELAYED` — 14 % de ses appels, le taux le plus haut du réseau.
- Pas de champ `Order` sur les `EstimatedCall` (confirmé). `DestinationDisplay` est en revanche
  présent sur chaque appel.

### Référentiel GTFS

- `route_type=1` donne **exactement 16 routes**, une par ligne commerciale, aucun
  `route_short_name` en doublon. La dérivation `IDFM:<code>` → `STIF:Line::<code>:` est valide
  sur les 16, toutes présentes dans le flux.
- **14 couleurs distinctes pour 16 lignes** : la 13 et la 3bis partagent `#82C8E6`, la 6 et la
  7bis `#82DC73` (le T4 aussi, à retenir pour le tram).
- `stop_times.txt` fait 909 Mo décompressé (10,5 M lignes) dont **941 959 pour le métro** ;
  **915** suffisent avec les seuls parcours représentatifs des branches retenues.
- Tracés : 112 candidats sur le métro → **37 retenus** par couverture gloutonne. Sans elle, la
  ligne 7 a 8 arrêts jusqu'à **1547 m** du tracé retenu. Trains écartés : 4,1 % → **0,6 %**.
- Stations : 781 quais, tous dotés d'un `parent_station` présent en `location_type=1` →
  **321 stations**, dont **61 correspondances** (jusqu'à 5 lignes).
```

- [ ] **Step 4: Mettre à jour `CLAUDE.md`**

Trois sections à reprendre :

- **« Configuration de la ligne suivie »** → la renommer « Configuration du réseau suivi » et décrire `app.network.modes` / `exclude`, la découverte par mode, et le fait que le `LINE_ID` du front n'existe plus.
- **« Données temps réel — pièges à connaître »** → corriger l'affirmation sur `OriginRef` (présent mais vide), signaler que `DatedVehicleJourneyRef` est toujours renseigné pour le métro, ajouter `RecordedAtTime` et le fait qu'il ne détecte pas une perturbation, et rappeler que le flux est désormais global et gzippé.
- **« Limitations connues »** → retirer la limitation « courses à un seul appel » de la liste des choses non traitées pour la reformuler en « signalée par `confidence`, non corrigée » ; retirer les branches (traitées) ; ajouter les 0,6 % de trains non plaçables, les couleurs partagées entre lignes, et le fait qu'aucun calendrier de service n'est chargé.

Ajouter aussi, dans « En deux mots », que le périmètre est le métro complet (16 lignes) et non plus la seule ligne 9.

- [ ] **Step 5: Vérifier la cohérence du README**

Run: `grep -n "lines/\|LINE_ID\|ligne 9" README.md`

Corriger toute référence aux endpoints supprimés (`/lines/{id}/shape`, `/lines/{id}/vehicles`, `/lines/{id}/stations/{sid}/departures`) au profit de `/network`, `/vehicles` et `/stations/{id}/departures`.

- [ ] **Step 6: Vérification finale complète**

Run: `cd backend && ./mvnw verify`
Expected: BUILD SUCCESS

Run: `cd frontend && npm run build`
Expected: build réussi

- [ ] **Step 7: Commit**

```bash
cd /home/abodet/workspace/perso/MapIDF
git add backend/docs/prim-integration.md CLAUDE.md README.md \
        docs/superpowers/plans/2026-07-29-multi-ligne-metro.md
git commit -m "docs: mesures du flux global, corrections PRIM et périmètre réseau

Consigne les mesures du 2026-07-29 dans prim-integration.md : volumétrie du
flux global, gzip supporté (3,96 Mo pour 45,6 Mo), 705 courses métro, et le
référentiel GTFS (16 routes en route_type=1, 941 959 stop_times métro
contre 915 nécessaires, 112 tracés candidats contre 37 retenus, 321
stations dont 61 correspondances).

Corrige trois affirmations de la doc : DatedVehicleJourneyRef est toujours
renseigné pour le métro, OriginRef est présent mais vide, et RecordedAtTime
existait sans être exploité — sans être pour autant un signal de
perturbation, la ligne 8 perturbée ayant la donnée la plus fraîche du
réseau.

Met CLAUDE.md à jour : configuration du réseau par mode, pièges temps réel
révisés, limitations connues recalées (branches traitées, courses à un seul
appel désormais signalées, couleurs partagées entre lignes)."
```

---

## Self-review

Relecture de la spec point par point, effectuée après rédaction.

**Couverture de la spec**

| Exigence de la spec | Tâche |
|---|---|
| `app.network.modes` / `exclude`, découverte par mode | 1, 5 |
| Dérivation du LineRef, id public `3b`/`7b`, couleur CSS | 2 |
| Couverture gloutonne des tracés | 3, 5 |
| Migration V4, `branch`, suppression de `trip` | 4 |
| Loader en deux passes, 915 `stop_time` | 5 |
| Stations parentes, nom déterministe | 5, 6 |
| `LineRegistry`, `LengthIndexedLine` préconstruit, index O(1) | 6 |
| Réhydratation depuis la base, base hors chemin de requête | 6, 7 |
| gzip, parse en streaming, timeout relevé, `recordedAt` | 8 |
| Choix de branche, `confidence`, `journeyRef`, métrique | 9 |
| `GET /network` | 10 |
| `GET /vehicles`, compression HTTP | 1, 11 |
| `GET /stations/{id}/departures` groupé par ligne, fusion des sens | 12 |
| Front : `/network`, deux couches, opacité des troncs | 13 |
| Front : image par couleur, opacité selon confiance, boucle allégée | 14 |
| Front : filtre par ligne, panneaux groupés, badge de retard | 15 |
| Mesure du temps de frame, docs | 16 |

**Points sans tâche dédiée, assumés**

- La **superposition géométrique des troncs** n'est pas déduplifiée : traitée par l'éclaircissement de couleur (tâche 13), comme la spec le prévoit.
- Les **couleurs partagées** entre la 13/3bis et la 6/7bis ne reçoivent aucun traitement : documenté en tâche 16.
- La piste de correction des **courses à un seul appel** reste hors périmètre, avec son ticket.

**Vérifications de cohérence effectuées**

- `Vehicle.journeyRef` (tâche 9) est bien le nom utilisé par `VehicleDto` (11), `promoteId` (14) et `onSelectTrain` (15).
- `LineBranch.indexOf` (6) est la seule voie de recherche d'arrêt ; `PositionEngine.indexOfStop` est supprimé (9).
- `GtfsStaticLoader.load(InputStream)` (5) remplace `loadFromZip(InputStream, String)` (4) : les IT des tâches 6, 7, 10, 11 et 12 utilisent tous la nouvelle forme.
- `useNetwork` prend un paramètre en tâche 13 et deux en tâche 15 : le changement est déclaré explicitement dans le bloc *Interfaces* de la tâche 15.
- `StationDepartureService.departures` change entièrement de signature en tâche 12 ; aucun appelant hors `StationsController` ne subsiste, `LineController` ayant été supprimé en tâche 4.
