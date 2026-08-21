# UX-5a — Recherche de station : plan d'implémentation

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ajouter un champ de recherche de station en tête du `LinePicker`, permettant d'atteindre
une station de la carte au clavier (sans souris), avec un résultat identique à un clic carte.

**Architecture:** Backend : un nouvel endpoint `GET /stations/search?q=...` qui scanne le registre
réseau déjà en mémoire (`LineRegistry`/`NetworkSnapshot`, le même qui sert `/network`) — aucune
requête DB. Frontend : un composant `StationSearch` (pattern ARIA combobox/listbox) qui réutilise
le flux de sélection existant du clic carte, extrait dans une fonction partagée `selectStation`.

**Tech Stack:** Spring Boot 4.1 / Java 25 (backend), React 19 / TypeScript 6 / Vitest 4 (frontend),
CSS Modules colocalisés.

**Spec:** [docs/superpowers/specs/2026-08-20-ux-5a-recherche-station-design.md](../specs/2026-08-20-ux-5a-recherche-station-design.md)

## Global Constraints

- Matching : sous-chaîne, insensible à la casse et aux accents (`java.text.Normalizer` NFD, pas de
  `unaccent` Postgres, pas de requête DB — le registre en mémoire suffit, cf. spec § 3).
- `normalizedName` est calculé **une fois**, à la construction de `Station` — jamais recalculé par
  recherche (spec § 3, raffinement post-brainstorm).
- Limite de résultats : **8** (`SEARCH_RESULTS_LIMIT`), pas de tri de pertinence au-delà de l'ordre
  du registre.
- Réutiliser les types existants plutôt qu'en dupliquer : `NetworkResponse.StationDto` côté
  backend, `NetworkStation` côté frontend (spec § 4-5).
- Aucune modification de `WebMvcConfiguration`/rate limiting n'est nécessaire au-delà d'un
  commentaire : l'interceptor SEC-3 couvre déjà `/**`.
- Pattern clavier : ARIA combobox/listbox complet, focus DOM **toujours** sur l'`<input>` — aucun
  résultat n'est directement focusable (`aria-activedescendant`).
- Focus après sélection : `focusMap()` **seulement** si `isNarrow` (le champ est démonté sur
  mobile) ; en desktop le focus reste sur le champ (spec § 6).
- Commits au format déjà utilisé par le dépôt : `type(ux-5a): message`.

---

## Task 1 : Backend — `Station.normalizedName` et `StationSearch` (TDD, pur)

**Files:**
- Modify: `backend/src/main/java/com/mapidf/network/Station.java`
- Create: `backend/src/main/java/com/mapidf/network/StationSearch.java`
- Create: `backend/src/test/java/com/mapidf/network/StationSearchTest.java`

**Interfaces:**
- Produces: `Station.normalizedName(): String` (composant de record, dérivé automatiquement) ;
  `StationSearch.search(List<Station> stations, String query, int limit): List<Station>` ;
  `StationSearch.normalize(String): String` (package-privé, réutilisé par `Station`).

- [ ] **Step 1: Écrire le test qui échoue**

Créer `backend/src/test/java/com/mapidf/network/StationSearchTest.java` :

```java
package com.mapidf.network;

import java.util.List;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class StationSearchTest {

    private static Station station(String name) {
        return new Station("ST", name, 0, 0, List.of(), List.of());
    }

    @Test
    void derivesTheNormalizedNameOnConstruction() {
        assertThat(station("Châtelet").normalizedName()).isEqualTo("chatelet");
    }

    @Test
    void findsAnAccentedNameFromAPlainQuery() {
        List<Station> stations = List.of(station("Châtelet"), station("Nation"));

        assertThat(StationSearch.search(stations, "chatelet", 8))
            .extracting(Station::name).containsExactly("Châtelet");
    }

    @Test
    void isCaseInsensitive() {
        List<Station> stations = List.of(station("Nation"));

        assertThat(StationSearch.search(stations, "NATION", 8))
            .extracting(Station::name).containsExactly("Nation");
    }

    @Test
    void matchesASubstringAnywhereInTheName() {
        List<Station> stations = List.of(station("Gare de Lyon"));

        assertThat(StationSearch.search(stations, "lyon", 8))
            .extracting(Station::name).containsExactly("Gare de Lyon");
    }

    @Test
    void rendersNoResultForABlankQuery() {
        List<Station> stations = List.of(station("Nation"));

        assertThat(StationSearch.search(stations, "", 8)).isEmpty();
        assertThat(StationSearch.search(stations, "   ", 8)).isEmpty();
    }

    @Test
    void rendersNoResultWhenNothingMatches() {
        List<Station> stations = List.of(station("Nation"));

        assertThat(StationSearch.search(stations, "zzz", 8)).isEmpty();
    }

    @Test
    void respectsTheLimit() {
        List<Station> stations =
            List.of(station("Alpha 1"), station("Alpha 2"), station("Alpha 3"));

        assertThat(StationSearch.search(stations, "alpha", 2)).hasSize(2);
    }
}
```

- [ ] **Step 2: Vérifier que ça ne compile pas**

Run: `cd backend && ./mvnw test-compile -Dtest=StationSearchTest`
Expected: échec de compilation — `normalizedName()` n'existe pas sur `Station`, et la classe
`StationSearch` n'existe pas.

- [ ] **Step 3: Implémenter `StationSearch`**

Créer `backend/src/main/java/com/mapidf/network/StationSearch.java` :

```java
package com.mapidf.network;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;

/**
 * Recherche de station par sous-chaîne, insensible à la casse et aux accents (UX-5a). Scanne le
 * registre déjà en mémoire — 321 stations aujourd'hui — plutôt qu'une requête DB : voir la spec
 * pour la justification chiffrée, y compris après un élargissement à RER/Transilien.
 */
public final class StationSearch {

    private StationSearch() {
    }

    public static List<Station> search(List<Station> stations, String query, int limit) {
        String needle = normalize(query);
        if (needle.isBlank()) {
            return List.of();
        }
        return stations.stream()
            .filter(station -> station.normalizedName().contains(needle))
            .limit(limit)
            .toList();
    }

    static String normalize(String s) {
        String stripped = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return stripped.toLowerCase(Locale.FRENCH);
    }
}
```

- [ ] **Step 4: Ajouter `normalizedName` à `Station`**

Remplacer le contenu de `backend/src/main/java/com/mapidf/network/Station.java` :

```java
package com.mapidf.network;

import java.util.List;

/**
 * Une station physique, dédoublonnée depuis ses quais. Mesuré sur le métro : 781 quais →
 * 321 stations, dont 61 correspondances (jusqu'à 5 lignes à République et Châtelet).
 */
public record Station(String id, String name, double lat, double lng,
                      List<String> platformIds, List<String> lineIds, String normalizedName) {
    public Station {
        platformIds = List.copyOf(platformIds);
        lineIds = List.copyOf(lineIds);
    }

    /** Conserve la signature existante : normalizedName se déduit, il ne s'invente pas ailleurs. */
    public Station(String id, String name, double lat, double lng,
                   List<String> platformIds, List<String> lineIds) {
        this(id, name, lat, lng, platformIds, lineIds, StationSearch.normalize(name));
    }
}
```

Les 6 sites de construction existants (`NetworkRegistryBuilder.java:125` et 5 fichiers de test)
utilisent tous le constructeur à 6 arguments : aucun autre fichier n'a besoin de changer.

- [ ] **Step 5: Vérifier que les tests passent**

Run: `cd backend && ./mvnw test -Dtest=StationSearchTest,NetworkSnapshotTest,LineRegistryTest,StationDepartureServiceTest`
Expected: BUILD SUCCESS, tous les tests passent (les 5 sites de construction existants n'ont pas
changé de signature d'appel).

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/mapidf/network/Station.java \
        backend/src/main/java/com/mapidf/network/StationSearch.java \
        backend/src/test/java/com/mapidf/network/StationSearchTest.java
git commit -m "feat(ux-5a): recherche de station par sous-chaine, normalisation en memoire"
```

---

## Task 2 : Backend — endpoint `/stations/search`

**Files:**
- Create: `backend/src/main/java/com/mapidf/controllers/stations/StationSearchResponse.java`
- Modify: `backend/src/main/java/com/mapidf/controllers/stations/StationsController.java`
- Modify: `backend/src/test/java/com/mapidf/controllers/stations/StationsControllerTest.java`

**Interfaces:**
- Consumes: `StationSearch.search(List<Station>, String, int): List<Station>` (Task 1) ;
  `NetworkResponse.StationDto(String id, String name, double lat, double lng, List<String> lineIds)`
  (`controllers/network/NetworkResponse.java:19`, déjà existant).
- Produces: `StationsController.search(String q): ResponseEntity<StationSearchResponse>`,
  route `GET /stations/search?q=...`.

- [ ] **Step 1: Écrire le test unitaire qui échoue**

Ajouter à `backend/src/test/java/com/mapidf/controllers/stations/StationsControllerTest.java`,
après `resolvesTheStationOnTheSnapshotThatKeysTheEntry` (et ajouter l'import
`com.mapidf.controllers.network.NetworkResponse` en tête du fichier) :

```java
    @Test
    void searchFindsAStationByASubstringOfItsName() {
        LineRegistry registry = new LineRegistry();
        registry.publish(NetworkSnapshot.of(List.of(),
            List.of(new Station("ST1", "Châtelet", 48.85, 2.34, List.of("1"), List.of("9")))));
        ObjectMapper json = new ObjectMapper();
        StationsController controller = new StationsController(registry,
            new RealtimePoller(PRIM, json, registry),
            new DisruptionPoller(PRIM, json, registry),
            new StationDepartureService(), FROZEN, new SimpleMeterRegistry());

        StationSearchResponse response = controller.search("chatelet").getBody();

        assertThat(response).isNotNull();
        assertThat(response.results()).extracting(NetworkResponse.StationDto::name)
            .containsExactly("Châtelet");
    }
```

- [ ] **Step 2: Vérifier que ça ne compile pas**

Run: `cd backend && ./mvnw test-compile -Dtest=StationsControllerTest`
Expected: échec — `StationsController.search(String)` n'existe pas, ni `StationSearchResponse`.

- [ ] **Step 3: Créer le DTO**

Créer `backend/src/main/java/com/mapidf/controllers/stations/StationSearchResponse.java` :

```java
package com.mapidf.controllers.stations;

import java.util.List;

import com.mapidf.controllers.network.NetworkResponse;

/** Résultats d'une recherche de station (UX-5a) ; réutilise le DTO déjà servi par `/network`. */
public record StationSearchResponse(List<NetworkResponse.StationDto> results) {
}
```

- [ ] **Step 4: Ajouter l'endpoint au contrôleur**

Dans `backend/src/main/java/com/mapidf/controllers/stations/StationsController.java`, ajouter
les imports :

```java
import com.mapidf.controllers.network.NetworkResponse;
import com.mapidf.network.StationSearch;
import org.springframework.web.bind.annotation.RequestParam;
```

Ajouter la constante à côté de `PASSAGES_PER_DIRECTION` (ligne 34) :

```java
    private static final int SEARCH_RESULTS_LIMIT = 8;
```

Ajouter la méthode, après `departures(...)` (après la ligne 73) :

```java
    @GetMapping("/stations/search")
    public ResponseEntity<StationSearchResponse> search(@RequestParam(defaultValue = "") String q) {
        List<Station> matches =
            StationSearch.search(registry.current().stations(), q, SEARCH_RESULTS_LIMIT);
        List<NetworkResponse.StationDto> items = matches.stream()
            .map(s -> new NetworkResponse.StationDto(s.id(), s.name(), s.lat(), s.lng(), s.lineIds()))
            .toList();
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(new StationSearchResponse(items));
    }
```

- [ ] **Step 5: Vérifier que le test passe**

Run: `cd backend && ./mvnw test -Dtest=StationsControllerTest`
Expected: BUILD SUCCESS.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/mapidf/controllers/stations/StationSearchResponse.java \
        backend/src/main/java/com/mapidf/controllers/stations/StationsController.java \
        backend/src/test/java/com/mapidf/controllers/stations/StationsControllerTest.java
git commit -m "feat(ux-5a): endpoint GET /stations/search"
```

---

## Task 3 : Backend — recette d'intégration et commentaire à jour

**Files:**
- Modify: `backend/src/test/java/com/mapidf/controllers/stations/StationsControllerIT.java`
- Modify: `backend/src/main/java/com/mapidf/configurations/WebMvcConfiguration.java`

**Interfaces:**
- Consumes: `GET /stations/search?q=...` (Task 2), fixture `gtfs-branch.zip` déjà chargée par
  `setup()` (stations connues : `ST1`/« Alpha », `STC`/« Correspondance », `ST3`/« Gamma »,
  `PT1`/« Nord », `PT3`/« Sud », `PT4`/« Villejuif », `PT5`/« Ivry »).

- [ ] **Step 1: Écrire les tests d'intégration qui échouent**

Ajouter à `backend/src/test/java/com/mapidf/controllers/stations/StationsControllerIT.java`,
avant la dernière accolade fermante de la classe :

```java
    @Test
    void searchFindsAStationByNameSubstring() throws Exception {
        mockMvc.perform(get("/stations/search").param("q", "orresp"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.results", hasSize(1)))
            .andExpect(jsonPath("$.results[0].id").value("STC"))
            .andExpect(jsonPath("$.results[0].name").value("Correspondance"));
    }

    @Test
    void searchIsCaseInsensitive() throws Exception {
        mockMvc.perform(get("/stations/search").param("q", "ALPHA"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.results[0].name").value("Alpha"));
    }

    @Test
    void searchRendersAnEmptyListWhenNothingMatches() throws Exception {
        mockMvc.perform(get("/stations/search").param("q", "zzz"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.results", hasSize(0)));
    }

    @Test
    void searchRendersAnEmptyListForAMissingQuery() throws Exception {
        mockMvc.perform(get("/stations/search"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.results", hasSize(0)));
    }

    @Test
    void searchResponseIsNeverStorable() throws Exception {
        mockMvc.perform(get("/stations/search").param("q", "alpha"))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"));
    }
```

Aucun nouvel import n'est nécessaire : `get`, `jsonPath`, `status`, `header` et `hasSize` sont
déjà importés en tête du fichier.

- [ ] **Step 2: Vérifier que ça échoue**

Run: `cd backend && JAVA_HOME=~/.jdks/temurin-25.0.4 ./mvnw verify -Dit.test=StationsControllerIT`
Expected: échec sur les 5 nouveaux tests (404, l'endpoint n'existe pas encore côté build testé) —
*seulement si Task 2 n'est pas encore committée dans cette exécution ; sinon, passer directement à
l'étape suivante puisque Task 2 rend déjà ces tests verts.*

- [ ] **Step 3: Mettre à jour le commentaire de `WebMvcConfiguration`**

Dans `backend/src/main/java/com/mapidf/configurations/WebMvcConfiguration.java`, remplacer :

```java
 * Sans motif de chemin : l'interceptor couvre les quatre endpoints. Que le contexte enfant de
```

par :

```java
 * Sans motif de chemin : l'interceptor couvre tous les endpoints publics. Que le contexte enfant de
```

(Un cinquième endpoint vient d'apparaître ; le chiffre en dur serait redevenu faux au prochain.)

- [ ] **Step 4: Vérifier que tout passe**

Run: `cd backend && JAVA_HOME=~/.jdks/temurin-25.0.4 ./mvnw verify`
Expected: BUILD SUCCESS, tous les tests unitaires et IT passent.

- [ ] **Step 5: Commit**

```bash
git add backend/src/test/java/com/mapidf/controllers/stations/StationsControllerIT.java \
        backend/src/main/java/com/mapidf/configurations/WebMvcConfiguration.java
git commit -m "test(ux-5a): recette d'integration de /stations/search"
```

---

## Task 4 : Frontend — extraire `selectStation` de `handleStationClick`

**Files:**
- Modify: `frontend/src/App.tsx:95-283` (zone concernée, cf. steps)

**Interfaces:**
- Produces: `selectStation(map: MlMap, id: string, coords: [number, number] | undefined): Promise<void>`
  (définie au niveau du composant, réutilisable) ; `selectStationFromSearch(id: string, coords:
  [number, number]): void` (à passer à `LinePicker` en Task 6).

Refactor à comportement strictement inchangé : `App.tsx` est hors du harnais Vitest (QUA-3), donc
aucun test automatique ne couvre ce fichier. La vérification se fait par `npm run build` (typage)
et une recette navigateur manuelle (cliquer une station sur la carte doit se comporter exactement
comme avant).

- [ ] **Step 1: Extraire `selectStation` au niveau du composant**

Dans `frontend/src/App.tsx`, juste après la définition de `openSheet` (après la ligne 117, avant
le `useEffect` qui pose les écouteurs de clic à la ligne 119), ajouter :

```tsx
  // Cœur de handleStationClick, extrait pour être rejoué depuis la recherche (UX-5a) exactement
  // comme depuis un clic carte : mêmes filtres, même vol de caméra, même fetch des passages.
  const selectStation = async (map: MlMap, id: string, coords: [number, number] | undefined) => {
    // Sélection exclusive : ouvrir une station ferme le suivi d'un train.
    setSelected(null);
    setSelectedJourneyRef(null);
    setFollow(false);
    map.setFilter("stops-selected", ["==", ["get", "id"], id]);
    // Avant l'easeTo : il doit s'animer vers le centre définitif, padding compris.
    openSheet(map);
    if (coords) {
      map.easeTo({ center: coords });
    }
    setSelectedStationId(id);
    departuresAbort.current?.abort();
    const controller = new AbortController();
    departuresAbort.current = controller;
    try {
      const fresh = await fetchDepartures(id, controller.signal);
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

- [ ] **Step 2: Réduire `handleStationClick` à un simple relais**

Dans le même fichier, remplacer le corps de `handleStationClick` (actuellement lignes 139-169) par :

```tsx
    const handleStationClick = (e: MapLayerMouseEvent) => {
      const id = e.features?.[0]?.properties?.id as string | undefined;
      const coords = (e.features?.[0]?.geometry as Point | undefined)?.coordinates as
        [number, number] | undefined;
      if (!id) {
        return;
      }
      void selectStation(map, id, coords);
    };
```

`map` référencé ici est celui narrowé non-nul par le `if (!map) { return; }` en tête de l'effet
(ligne 120) : ne pas le confondre avec le paramètre `map` de `selectStation`, qui reste explicite
pour rester appelable hors de cet effet (même motif que `openSheet`).

- [ ] **Step 3: Ajouter `selectStationFromSearch`, après `focusMap`**

Dans `frontend/src/App.tsx`, juste après la définition de `focusMap` (ligne 263, avant
`resetStation`), ajouter :

```tsx
  // Pont entre la recherche (UX-5a) et la sélection existante. Sur mobile, le champ de recherche
  // est démonté dès qu'une station est sélectionnée (Sheet à contenu unique, App.tsx:407) : sans
  // retour explicite le focus retomberait sur `body`, même défaut que closeStation corrige à la
  // fermeture. En desktop LinePicker et sa recherche survivent à la sélection (FloatingCard
  // séparée) : le focus reste sur le champ, pour pouvoir enchaîner une deuxième recherche.
  const selectStationFromSearch = (id: string, coords: [number, number]) => {
    if (!map) {
      return;
    }
    void selectStation(map, id, coords);
    if (isNarrow) {
      focusMap();
    }
  };
```

- [ ] **Step 4: Vérifier le typage**

Run: `cd frontend && npm run build`
Expected: build réussi, aucune erreur `tsc`.

- [ ] **Step 5: Recette navigateur manuelle**

Lancer `npm run dev`, cliquer une station sur la carte : la fiche s'ouvre, la caméra se déplace,
les passages se chargent — comportement identique à avant le refactor. Cliquer un train doit
rester inchangé (chemin non touché par cette tâche).

- [ ] **Step 6: Commit**

```bash
git add frontend/src/App.tsx
git commit -m "refactor(ux-5a): extraire selectStation du clic carte, sans changement de comportement"
```

---

## Task 5 : Frontend — API layer et composant `StationSearch` (TDD)

**Files:**
- Modify: `frontend/src/api/config.ts`
- Modify: `frontend/src/api/types.ts`
- Modify: `frontend/src/api/network.ts`
- Create: `frontend/src/ui/StationSearch.tsx`
- Create: `frontend/src/ui/StationSearch.module.css`
- Create: `frontend/src/ui/StationSearch.test.tsx`

**Interfaces:**
- Consumes: `NetworkStation` (`api/types.ts:15-21`, déjà existant).
- Produces: `searchStations(q: string, signal?: AbortSignal): Promise<StationSearchResponse>` ;
  `StationSearch({ onSelectStation }: { onSelectStation: (id: string, coords: [number, number]) =>
  void })`, exporté pour l'intégration en Task 6.

- [ ] **Step 1: Ajouter le type et la fonction d'API**

Dans `frontend/src/api/types.ts`, ajouter à la fin du fichier :

```ts
/** Résultats d'une recherche de station (UX-5a) ; mêmes champs que ceux de `/network`. */
export interface StationSearchResponse {
  results: NetworkStation[];
}
```

Dans `frontend/src/api/config.ts`, ajouter :

```ts
export const SEARCH_DEBOUNCE_MS = 200;
```

Dans `frontend/src/api/network.ts`, ajouter à l'import de types :

```ts
import type { NetworkResponse, VehiclesResponse, DeparturesResponse, DisruptionsResponse, StationSearchResponse } from "./types";
```

et la fonction, à la fin du fichier :

```ts
export function searchStations(q: string, signal?: AbortSignal): Promise<StationSearchResponse> {
  return getJson<StationSearchResponse>(`/stations/search?q=${encodeURIComponent(q)}`, signal);
}
```

- [ ] **Step 2: Écrire le test du composant qui échoue**

Créer `frontend/src/ui/StationSearch.test.tsx` :

```tsx
// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { NetworkStation } from "../api/types";
import { expectNoA11yViolations } from "../test/axe";
import { StationSearch } from "./StationSearch";
import { searchStations } from "../api/network";

vi.mock("../api/network", () => ({
  searchStations: vi.fn(),
}));

afterEach(cleanup);

const ALPHA: NetworkStation = { id: "ST1", name: "Alpha", lat: 48.85, lng: 2.30, lineIds: ["9"] };
const GAMMA: NetworkStation = { id: "ST3", name: "Gamma", lat: 48.86, lng: 2.32, lineIds: ["7"] };

function renderSearch() {
  const onSelectStation = vi.fn();
  const result = render(<StationSearch onSelectStation={onSelectStation} />);
  return { onSelectStation, ...result };
}

describe("StationSearch", () => {
  it("n'affiche aucune liste avant la frappe", () => {
    renderSearch();

    expect(screen.queryByRole("listbox")).toBeNull();
  });

  it("affiche les résultats après la frappe, une fois le debounce écoulé", async () => {
    vi.mocked(searchStations).mockResolvedValue({ results: [ALPHA, GAMMA] });
    renderSearch();

    fireEvent.change(screen.getByRole("combobox"), { target: { value: "a" } });

    expect(await screen.findByRole("option", { name: "Alpha" })).not.toBeNull();
    expect(screen.getByRole("option", { name: "Gamma" })).not.toBeNull();
    expect(searchStations).toHaveBeenCalledWith("a", expect.any(AbortSignal));
  });

  it("la flèche bas déplace aria-activedescendant sur le premier résultat", async () => {
    vi.mocked(searchStations).mockResolvedValue({ results: [ALPHA, GAMMA] });
    renderSearch();
    const input = screen.getByRole("combobox");
    fireEvent.change(input, { target: { value: "a" } });
    await screen.findByRole("option", { name: "Alpha" });

    fireEvent.keyDown(input, { key: "ArrowDown" });

    expect(input.getAttribute("aria-activedescendant")).toBe("station-option-ST1");
    expect(screen.getByRole("option", { name: "Alpha" }).getAttribute("aria-selected")).toBe("true");
    expect(screen.getByRole("option", { name: "Gamma" }).getAttribute("aria-selected")).toBe("false");
  });

  it("Entrée sélectionne l'option courante et referme la liste", async () => {
    vi.mocked(searchStations).mockResolvedValue({ results: [ALPHA] });
    const { onSelectStation } = renderSearch();
    const input = screen.getByRole("combobox");
    fireEvent.change(input, { target: { value: "alp" } });
    await screen.findByRole("option", { name: "Alpha" });
    fireEvent.keyDown(input, { key: "ArrowDown" });

    fireEvent.keyDown(input, { key: "Enter" });

    expect(onSelectStation).toHaveBeenCalledExactlyOnceWith("ST1", [2.30, 48.85]);
    expect(screen.queryByRole("listbox")).toBeNull();
    expect((input as HTMLInputElement).value).toBe("");
  });

  it("un clic sur un résultat sélectionne aussi la station", async () => {
    vi.mocked(searchStations).mockResolvedValue({ results: [ALPHA] });
    const { onSelectStation } = renderSearch();
    fireEvent.change(screen.getByRole("combobox"), { target: { value: "alp" } });
    await screen.findByRole("option", { name: "Alpha" });

    fireEvent.click(screen.getByRole("option", { name: "Alpha" }));

    expect(onSelectStation).toHaveBeenCalledExactlyOnceWith("ST1", [2.30, 48.85]);
  });

  it("Échap referme la liste sans sélectionner", async () => {
    vi.mocked(searchStations).mockResolvedValue({ results: [ALPHA] });
    const { onSelectStation } = renderSearch();
    const input = screen.getByRole("combobox");
    fireEvent.change(input, { target: { value: "alp" } });
    await screen.findByRole("option", { name: "Alpha" });

    fireEvent.keyDown(input, { key: "Escape" });

    expect(screen.queryByRole("listbox")).toBeNull();
    expect(onSelectStation).not.toHaveBeenCalled();
  });

  it("Échap ne remonte pas au document quand la liste est ouverte", async () => {
    // Le projet ferme la fiche courante sur un Échap global (App.tsx, écouteur document). Si cet
    // événement remontait, sélectionner une station depuis la recherche fermerait aussi la fiche
    // affichée à côté sur desktop — un couplage non voulu entre deux panneaux indépendants.
    vi.mocked(searchStations).mockResolvedValue({ results: [ALPHA] });
    renderSearch();
    const documentEscape = vi.fn();
    document.addEventListener("keydown", documentEscape);
    const input = screen.getByRole("combobox");
    fireEvent.change(input, { target: { value: "alp" } });
    await screen.findByRole("option", { name: "Alpha" });

    fireEvent.keyDown(input, { key: "Escape" });

    expect(documentEscape).not.toHaveBeenCalled();
    document.removeEventListener("keydown", documentEscape);
  });

  it("Échap remonte au document quand la recherche est vide", () => {
    renderSearch();
    const documentEscape = vi.fn();
    document.addEventListener("keydown", documentEscape);

    fireEvent.keyDown(screen.getByRole("combobox"), { key: "Escape" });

    expect(documentEscape).toHaveBeenCalledOnce();
    document.removeEventListener("keydown", documentEscape);
  });

  it("ne présente aucune violation détectable par axe", async () => {
    vi.mocked(searchStations).mockResolvedValue({ results: [ALPHA, GAMMA] });
    renderSearch();
    fireEvent.change(screen.getByRole("combobox"), { target: { value: "a" } });
    await screen.findByRole("option", { name: "Alpha" });

    await expectNoA11yViolations();
  });
});
```

- [ ] **Step 3: Vérifier que ça échoue**

Run: `cd frontend && npx vitest run src/ui/StationSearch.test.tsx`
Expected: échec — `./StationSearch` n'existe pas encore.

- [ ] **Step 4: Implémenter `StationSearch.module.css`**

Créer `frontend/src/ui/StationSearch.module.css` :

```css
.search {
  position: relative;
  margin-bottom: 8px;
}

/* `font-family`/`color` explicites : un `input`, comme un `<button>` (cf. CLAUDE.md), n'hérite pas
   nécessairement la police ni la couleur du document selon le moteur. */
.input {
  width: 100%;
  box-sizing: border-box;
  padding: 6px 8px;
  border: 1px solid var(--border);
  border-radius: 6px;
  background: var(--surface);
  color: var(--text);
  font-family: var(--font);
  font-size: 13px;
  min-height: var(--tap);
}

.srOnly {
  position: absolute;
  width: 1px;
  height: 1px;
  margin: -1px;
  padding: 0;
  overflow: hidden;
  white-space: nowrap;
  border: 0;
  clip: rect(0, 0, 0, 0);
}

.results {
  position: absolute;
  z-index: 1;
  left: 0;
  right: 0;
  margin: 4px 0 0;
  padding: 4px 0;
  list-style: none;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: 6px;
  box-shadow: var(--shadow-card);
  max-height: 240px;
  overflow-y: auto;
}

.result {
  padding: 6px 10px;
  font-family: var(--font);
  font-size: 13px;
  color: var(--text);
  cursor: pointer;
}

.result[aria-selected="true"] {
  background: var(--surface-off);
}
```

- [ ] **Step 5: Implémenter `StationSearch.tsx`**

Créer `frontend/src/ui/StationSearch.tsx` :

```tsx
import { useEffect, useRef, useState } from "react";
import type { KeyboardEvent } from "react";
import { searchStations } from "../api/network";
import { SEARCH_DEBOUNCE_MS } from "../api/config";
import type { NetworkStation } from "../api/types";
import styles from "./StationSearch.module.css";

interface Props {
  onSelectStation: (id: string, coords: [number, number]) => void;
}

/**
 * Recherche de station : point d'entrée clavier pour atteindre une entité de la carte sans souris
 * (UX-5a, dette héritée d'UX-4 — un canevas MapLibre n'a pas d'enfants focusables). Pattern ARIA
 * combobox/listbox : le focus DOM reste sur l'input du début à la fin, aucun résultat n'est
 * focusable directement — la sélection se pilote par `aria-activedescendant`.
 */
export function StationSearch({ onSelectStation }: Props) {
  const [query, setQuery] = useState("");
  const [results, setResults] = useState<NetworkStation[]>([]);
  const [activeIndex, setActiveIndex] = useState(-1);
  const abortRef = useRef<AbortController | null>(null);

  useEffect(() => {
    const trimmed = query.trim();
    if (!trimmed) {
      setResults([]);
      setActiveIndex(-1);
      return;
    }
    const timer = window.setTimeout(() => {
      abortRef.current?.abort();
      const controller = new AbortController();
      abortRef.current = controller;
      searchStations(trimmed, controller.signal)
        .then((response) => {
          if (!controller.signal.aborted) {
            setResults(response.results);
            setActiveIndex(-1);
          }
        })
        .catch(() => {
          if (!controller.signal.aborted) {
            setResults([]);
          }
        });
    }, SEARCH_DEBOUNCE_MS);
    return () => window.clearTimeout(timer);
  }, [query]);

  const open = results.length > 0;

  const select = (station: NetworkStation) => {
    onSelectStation(station.id, [station.lng, station.lat]);
    setQuery("");
    setResults([]);
    setActiveIndex(-1);
  };

  const onKeyDown = (e: KeyboardEvent<HTMLInputElement>) => {
    if (e.key === "ArrowDown" && open) {
      e.preventDefault();
      setActiveIndex((i) => Math.min(i + 1, results.length - 1));
    } else if (e.key === "ArrowUp" && open) {
      e.preventDefault();
      setActiveIndex((i) => Math.max(i - 1, 0));
    } else if (e.key === "Enter" && open && activeIndex >= 0) {
      e.preventDefault();
      select(results[activeIndex]);
    } else if (e.key === "Escape" && (open || query)) {
      // Empêche l'Échap global de l'app (fermeture de fiche, App.tsx) de réagir à un geste qui ne
      // visait que la recherche : les deux panneaux sont indépendants sur desktop.
      e.stopPropagation();
      setQuery("");
      setResults([]);
      setActiveIndex(-1);
    }
  };

  const activeId = activeIndex >= 0 ? `station-option-${results[activeIndex].id}` : undefined;

  return (
    <div className={styles.search}>
      <input
        type="text"
        className={styles.input}
        role="combobox"
        aria-expanded={open}
        aria-controls="station-search-listbox"
        aria-activedescendant={activeId}
        aria-autocomplete="list"
        aria-label="Rechercher une station"
        placeholder="Rechercher une station…"
        value={query}
        onChange={(e) => setQuery(e.target.value)}
        onKeyDown={onKeyDown}
      />
      <p className={styles.srOnly} aria-live="polite">
        {open ? `${results.length} résultat${results.length > 1 ? "s" : ""}` : ""}
      </p>
      {/* Toujours montée, masquée par `hidden` : convention du projet (index.css porte la garde
          `[hidden] { display: none !important }`), et ça évite un `aria-controls` qui pointerait
          par moments vers un id absent du DOM. */}
      <ul className={styles.results} role="listbox" id="station-search-listbox" hidden={!open}>
        {results.map((station, index) => (
          <li
            key={station.id}
            id={`station-option-${station.id}`}
            role="option"
            aria-selected={index === activeIndex}
            className={styles.result}
            onMouseEnter={() => setActiveIndex(index)}
            onClick={() => select(station)}
          >
            {station.name}
          </li>
        ))}
      </ul>
    </div>
  );
}
```

- [ ] **Step 6: Vérifier que les tests passent**

Run: `cd frontend && npx vitest run src/ui/StationSearch.test.tsx`
Expected: tous les tests passent.

- [ ] **Step 7: Lint et typage**

Run: `cd frontend && npm run lint && npm run build`
Expected: les deux muets/verts.

- [ ] **Step 8: Commit**

```bash
git add frontend/src/api/config.ts frontend/src/api/types.ts frontend/src/api/network.ts \
        frontend/src/ui/StationSearch.tsx frontend/src/ui/StationSearch.module.css \
        frontend/src/ui/StationSearch.test.tsx
git commit -m "feat(ux-5a): composant StationSearch, pattern ARIA combobox/listbox"
```

---

## Task 6 : Frontend — intégration dans `LinePicker`/`App.tsx` et vérification finale

**Files:**
- Modify: `frontend/src/ui/LinePicker.tsx`
- Modify: `frontend/src/ui/LinePicker.test.tsx`
- Modify: `frontend/src/App.tsx`

**Interfaces:**
- Consumes: `StationSearch` (Task 5), `selectStationFromSearch` (Task 4).

- [ ] **Step 1: Mettre à jour le test de `LinePicker` pour la nouvelle prop**

Dans `frontend/src/ui/LinePicker.test.tsx`, dans `renderPicker`, ajouter `onSelectStation:
vi.fn()` par défaut :

```tsx
function renderPicker(props: Partial<Parameters<typeof LinePicker>[0]> = {}) {
  const onToggle = vi.fn();
  const onSelectStation = vi.fn();
  const result = render(
    <LinePicker
      lines={[LIGNE_9, LIGNE_8]}
      disrupted={[]}
      counts={new Map([[LIGNE_9.id, 12], [LIGNE_8.id, 7]])}
      disruptions={new Map()}
      disruptionsOpen={false}
      visible={null}
      onToggle={onToggle}
      onSelectStation={onSelectStation}
      {...props}
    />,
  );
  return { onToggle, onSelectStation, ...result };
}
```

- [ ] **Step 2: Vérifier que ça ne compile pas**

Run: `cd frontend && npx vitest run src/ui/LinePicker.test.tsx`
Expected: échec de typage — `LinePicker` n'a pas encore de prop `onSelectStation`.

- [ ] **Step 3: Ajouter la prop et le rendu dans `LinePicker`**

Dans `frontend/src/ui/LinePicker.tsx`, ajouter l'import :

```tsx
import { StationSearch } from "./StationSearch";
```

Ajouter à l'interface `Props` :

```tsx
  onSelectStation: (id: string, coords: [number, number]) => void;
```

Déstructurer le nouveau prop dans la signature de la fonction :

```tsx
export function LinePicker({
  lines, disrupted, counts, disruptions, disruptionsOpen, visible, onToggle, onSelectStation,
}: Props) {
```

Et l'utiliser en tête du rendu, avant le bloc `{disruptionsOpen && ...}` :

```tsx
  return (
    <>
      <StationSearch onSelectStation={onSelectStation} />
      {disruptionsOpen && disrupted.length > 0 && (
```

- [ ] **Step 4: Vérifier que les tests `LinePicker` passent**

Run: `cd frontend && npx vitest run src/ui/LinePicker.test.tsx`
Expected: tous les tests passent (le combobox de `StationSearch` apparaît désormais dans chaque
rendu, sans perturber les assertions existantes sur les pastilles de ligne — elles ciblent par
`title`/`role=button`, jamais par position).

- [ ] **Step 5: Câbler `selectStationFromSearch` depuis `App.tsx`**

Dans `frontend/src/App.tsx`, sur la construction de `linePicker` (ligne 344-354), ajouter la
prop :

```tsx
  const linePicker = (
    <LinePicker
      lines={orderedLines}
      disrupted={disrupted}
      counts={counts}
      disruptions={disruptions.byLine}
      disruptionsOpen={disruptionsOpen}
      visible={visibleLines}
      onToggle={toggleLine}
      onSelectStation={selectStationFromSearch}
    />
  );
```

- [ ] **Step 6: Vérification complète**

Run : `cd frontend && npm run lint && npm run build && npx vitest run`
Expected : lint muet, build réussi, tous les tests verts.

Run (backend, si pas déjà fait dans cette session) : `cd backend && JAVA_HOME=~/.jdks/temurin-25.0.4 ./mvnw verify`
Expected : BUILD SUCCESS.

- [ ] **Step 7: Recette navigateur manuelle (jsdom ne peut pas la remplacer)**

Lancer `npm run dev`, puis vérifier, en desktop ET en mobile (redimensionner < 720 px) :
- Taper un nom de station (avec et sans accent, ex. « chatelet » / « Châtelet ») affiche les bons
  résultats.
- Flèches + Entrée sélectionnent une station : la fiche s'ouvre, la caméra se déplace, comme un
  clic carte.
- En desktop, après sélection, le focus visuel reste sur le champ de recherche (l'anneau
  `:focus-visible` doit y rester visible).
- En mobile, après sélection, la feuille remplace le sélecteur par la fiche station, et le focus
  visuel atterrit sur le canevas de la carte (pas sur `body`).
- Échap dans le champ de recherche ferme uniquement la liste ; s'il y a une fiche ouverte à côté
  (desktop), elle reste ouverte.
- Le champ hérite bien la police et la couleur du thème (clair et sombre).

- [ ] **Step 8: Commit**

```bash
git add frontend/src/ui/LinePicker.tsx frontend/src/ui/LinePicker.test.tsx frontend/src/App.tsx
git commit -m "feat(ux-5a): cabler la recherche de station dans LinePicker"
```

- [ ] **Step 9: Mettre à jour la roadmap**

Dans `docs/roadmap.md`, faire passer le statut de la ligne UX-5a de « en cours » à « fait », avec
un résumé bref de ce qui a été livré et des limitations constatées en recette (Step 7).

```bash
git add docs/roadmap.md
git commit -m "docs(ux-5a): chantier recherche de station cloture"
```
