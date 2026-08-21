# UX-5a — Recherche de station : conception

Chantier UX-5a de la [feuille de route](../../roadmap.md). Conception arrêtée le **2026-08-20**,
à l'issue d'un découpage d'UX-5 en cinq sous-chantiers indépendants (UX-5a à UX-5e) : la fiche
d'origine empaquetait recherche de station, permalien dans l'URL, « trains autour de moi », sens
des tracés et plus de 3 passages par direction — cinq pistes sans dépendance forte entre elles,
suivant le précédent déjà posé par UX-3a/UX-3b.

## 1. Objectif et critère de réussite

Donner un point d'entrée clavier pour atteindre une **entité** de la carte (une station) sans
souris. C'est la dette explicitement laissée par UX-4 : la carte est pilotable au clavier (MapLibre
pose `tabindex="0"` sur son canevas), mais un canevas n'a pas d'enfants focusables — impossible d'y
atteindre une station précise sans cliquer dessus. Une recherche texte, avec des résultats dans une
vraie liste DOM, résout ce point sans toucher à la carte elle-même.

Critère de réussite : taper un nom de station (avec ou sans accent, avec ou sans majuscule),
choisir un résultat au clavier ou à la souris, obtenir exactement ce que produit un clic sur cette
station sur la carte — vol de caméra, ouverture de la fiche, passages à jour.

## 2. Ce qui décide de la conception

Décisions prises en brainstorming, qui ne sont pas rediscutées ici :

- **Emplacement** : un champ en tête de `LinePicker` — panneau déjà permanent, donc point d'entrée
  visible sans état supplémentaire à câbler pour l'afficher/le masquer.
- **Source des données** : un nouvel endpoint backend `GET /stations/search`, pas un filtrage
  client sur les données déjà chargées par `/network`. Choix délibéré malgré le coût nul du
  filtrage client (321 stations tiennent en mémoire) : garder la logique de recherche côté serveur
  laisse la porte ouverte à un tri de pertinence ou une volumétrie plus grande (PROD-2, tram/RER)
  sans toucher au contrat de `/network`.
- **Matching** : sous-chaîne, insensible à la casse et aux accents. Pas de préfixe seul (trop
  restrictif : « chatelet » ne doit pas exiger de commencer par « ch »), pas de recherche floue
  (Levenshtein/trigram — complexité de réglage disproportionnée pour ~321 noms).
- **Clavier** : pattern ARIA combobox/listbox complet (`aria-expanded`, `aria-activedescendant`,
  `role="listbox"`/`option`), cohérent avec le niveau d'accessibilité posé par UX-4.

## 3. Backend : chercher dans le registre déjà en mémoire, pas dans PostGIS

`LineRegistry` (`backend/src/main/java/com/mapidf/network/LineRegistry.java`) publie déjà un
`NetworkSnapshot` immuable contenant `stations(): List<Station>` — le même registre que sert
`GET /network` (`NetworkController.java:30`). Chaque `Station` (`network/Station.java`) porte déjà
`id`, `name`, `lat`, `lng`, `lineIds`, dédoublonnée par station parente (321 stations pour 781
quais, `Station.java:6-7`).

Chercher dans ce registre déjà construit évite tout ce qu'une recherche en base aurait coûté :
- pas de nouvelle migration Flyway ni d'extension Postgres `unaccent` (absente du projet à ce
  jour — la seule extension chargée est `postgis`, `V1__network_schema.sql:1`) ;
- pas de requête SQL native (le projet n'a que du JPQL `@Query`, cf. `StopTimeRepository.java`,
  aucun précédent pour une fonction Postgres non standard) ;
- un scan linéaire sur ~321 records immuables, tenus en mémoire, est de l'ordre de la
  microseconde — sans commune mesure avec le coût qui a justifié `ResponseCache` sur `/vehicles`
  (PERF-3, sérialisation de 705 véhicules à chaque appel). **Ce chantier n'a donc pas besoin de
  cache** : contrairement aux trois endpoints de PERF-3, le corps varie avec `q` (une entrée de
  cache par requête distincte n'apporterait rien face à un calcul déjà sous la milliseconde).

Nouvelle classe pure, testable sans Spring : `backend/src/main/java/com/mapidf/network/StationSearch.java`.

**Raffinement retenu après relecture** : normaliser (accents/casse) le nom de chaque station
**une seule fois**, au moment où le registre est construit — pas à chaque frappe. `Station`
(`network/Station.java`) gagne un composant `normalizedName`, calculé par un constructeur de
commodité qui garde intacte la signature à 6 arguments déjà utilisée aux 6 sites de construction
du projet (`NetworkRegistryBuilder.java:125` et 5 tests) :

```java
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

```java
public final class StationSearch {
    private StationSearch() {}

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
        String stripped = Normalizer.normalize(s, Normalizer.Form.NFD)
            .replaceAll("\\p{M}", "");
        return stripped.toLowerCase(Locale.FRENCH);
    }
}
```

Avec ce découpage, une recherche ne normalise plus qu'**une chaîne** par appel (`query`) — les 321
`normalizedName` sont déjà prêts, calculés une fois par refresh GTFS (une fois par jour). Même
logique que `LineBadge`/`readableOn` côté front (`ui/color.ts`) : une fonction pure, sans
dépendance, directement couverte par des tests unitaires sur des cas mesurés (« chatelet » trouve
« Châtelet », « NATION » trouve « Nation »). L'ordre de retour est celui du registre (pas de tri de
pertinence — tranché en § 2) ; `limit` borne simplement la taille de la réponse, quelle que soit la
longueur de `query` — une saisie d'un seul caractère peut matcher des dizaines de stations, `limit`
évite de toutes les sérialiser et de toutes les rendre dans la liste.

**Ce raffinement tient-il encore après PROD-2 (tram, puis RER/Transilien) ?** Estimation **non
mesurée** (aucun chiffre RER/Transilien n'existe dans le projet à ce jour — à vérifier sur le vrai
GTFS `stops.txt` quand PROD-2 sera cadré, pas avant) : le réseau lourd complet d'Île-de-France
(métro + RER + Transilien + tram) tourne probablement autour de **1 500 à 2 500 stations
parentes**, contre 321 aujourd'hui pour le seul métro — un ordre de grandeur de plus, pas trois.
Un scan linéaire sur quelques milliers de chaînes courtes déjà normalisées reste de l'ordre de la
dizaine de microsecondes, toujours très inférieur au plancher d'un aller-retour DB (connexion,
plan de requête, réseau — de l'ordre de la milliseconde). Et l'argument « un index aiderait » ne
tient pas mieux à cette échelle : un index B-tree standard n'accélère pas une recherche en
**sous-chaîne** (`LIKE '%x%'` force un scan séquentiel côté Postgres) — il faudrait l'extension
`pg_trgm` (trigramme), une pièce d'infrastructure de plus, en plus d'`unaccent`, pour un problème
de volumétrie qui n'existe pas encore. Le seuil où un index trigramme deviendrait réellement utile
se compte en dizaines de milliers de lignes — l'échelle des arrêts de bus (~40 000 sur toute l'Île-
de-France), hors du périmètre `app.network.modes` actuel et bien au-delà de ce que PROD-2 propose.
**Conclusion : ne pas basculer vers une recherche DB à l'occasion de PROD-2**, cette question ne se
reposerait que si le périmètre suivi s'étendait un jour aux bus.

## 4. Contrat de l'endpoint

`GET /stations/search?q=...` dans `StationsController` (même contrôleur que
`/stations/{id}/departures`, même style d'injection de `LineRegistry` déjà présent).

**Pas de nouveau DTO** : `NetworkResponse.StationDto` (`controllers/network/NetworkResponse.java:19`)
a déjà exactement la forme requise — `(id, name, lat, lng, lineIds)`, la même que celle que
`/network` sert déjà pour chaque station. Le réutiliser directement à travers les deux packages
suit le précédent déjà posé par `DeparturesResponse`, qui importe et réutilise
`DisruptionsResponse.Item` plutôt que de dupliquer un type identique.

```java
private static final int SEARCH_RESULTS_LIMIT = 8;

@GetMapping("/stations/search")
public ResponseEntity<StationSearchResponse> search(
        @RequestParam(defaultValue = "") String q) {
    List<Station> matches = StationSearch.search(
        registry.current().stations(), q, SEARCH_RESULTS_LIMIT);
    List<NetworkResponse.StationDto> items = matches.stream()
        .map(s -> new NetworkResponse.StationDto(s.id(), s.name(), s.lat(), s.lng(), s.lineIds()))
        .toList();
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new StationSearchResponse(items));
}
```

```java
public record StationSearchResponse(List<NetworkResponse.StationDto> results) {}
```

`lat`/`lng` sont nécessaires dans la réponse : c'est ce qui permet au front de rejouer le vol de
caméra sans second appel (§ 5). `q` manquant → chaîne vide → `StationSearch.search` la détecte
(`isBlank`) et rend une liste vide sans exception, ni cas particulier dans le contrôleur.

**Rate limiting** : automatique, sans rien ajouter. `RateLimitInterceptor` est enregistré sans
`PathPattern` dans `WebMvcConfiguration.java:23-25` — il couvre déjà tout `/**`, ce nouvel endpoint
inclus.

## 5. Frontend : composant `StationSearch` et réutilisation de la sélection existante

**Extraction préalable dans `App.tsx`** : la sélection d'une station est aujourd'hui tout entière
dans `handleStationClick` (`App.tsx:139-169`), qui lit `id` et les coordonnées depuis
l'événement MapLibre. Pour que la recherche déclenche *exactement* le même comportement (fermeture
du train suivi, filtre `stops-selected`, `openSheet`, vol de caméra, fetch des passages), cette
fonction est scindée :

- `selectStation(id: string, coords: [number, number])` — le corps actuel de
  `handleStationClick` à partir de la ligne 145, sans changement de logique.
- Le handler de clic MapLibre devient un wrapper de trois lignes qui extrait `id`/`coords` de
  l'événement puis appelle `selectStation`.

`selectStation` reste l'appel du clic carte (le clic focalise déjà nativement le canevas cliqué,
rien à ajouter). La recherche, elle, reçoit un wrapper distinct — `selectStationFromSearch`,
§ 6 — passé à `LinePicker` puis à `StationSearch` via une prop `onSelectStation`, sur le même
principe que `onToggle` aujourd'hui.

**Nouveau composant** `ui/StationSearch.tsx` + `StationSearch.module.css` :
- Un `<input type="text">` — **premier champ texte du projet** ; aucun composant existant n'en a,
  donc aucun précédent CSS à réutiliser. À vérifier en navigateur, comme mesuré pour `<button>`
  (CLAUDE.md) : un `input` n'hérite pas nécessairement `font`/`color` du document selon le moteur,
  la feuille de l'UA pouvant lui poser les deux explicitement.
- Debounce ~200 ms avant d'appeler `searchStations`, dès 1 caractère saisi — pas de hook dédié à
  écrire, le projet n'en a pas et le pattern `window.setTimeout` + cleanup à l'`useEffect` est déjà
  celui du polling (`App.tsx:236-256`) ; un `AbortController` annule une requête en vol si l'usager
  retape avant la réponse.
- Pattern ARIA combobox : `role="combobox"` + `aria-expanded` + `aria-controls` sur l'input,
  `role="listbox"` sur la liste de résultats, chaque résultat `role="option"` avec
  `aria-selected`. Navigation par `aria-activedescendant` : les flèches haut/bas déplacent un index
  local, Entrée sélectionne l'option courante, Échap vide et referme la liste — le focus DOM reste
  sur l'input du début à la fin, aucun élément de la liste n'est jamais focusable directement.
- `aria-live="polite"` sur un texte annonçant le nombre de résultats (« 3 résultats »), pour qu'un
  lecteur d'écran sache que la frappe a produit un effet sans devoir naviguer la liste.

`api/network.ts` reçoit :
```ts
export function searchStations(q: string, signal?: AbortSignal): Promise<StationSearchResponse> {
  return getJson<StationSearchResponse>(`/stations/search?q=${encodeURIComponent(q)}`, signal);
}
```
et `api/types.ts` un type `StationSearchResponse` qui réutilise directement `NetworkStation`
(`api/types.ts:15-21`, déjà exactement `{id, name, lat, lng, lineIds}`) plutôt que d'en dupliquer
un jumeau :
```ts
export interface StationSearchResponse {
  results: NetworkStation[];
}
```

**Sélection d'un résultat** appelle `onSelectStation(item.id, [item.lng, item.lat])`, qui vide le
champ de saisie et referme la liste.

## 6. Focus après sélection : mobile et desktop divergent, et c'est voulu

Le rendu de `LinePicker` (donc de la recherche) dépend du support (`App.tsx:394-428`) :

- **Desktop** : `LinePicker` vit dans sa propre `FloatingCard` (« État du réseau »,
  `App.tsx:421-426`), indépendante de la fiche station (`FloatingCard` « Détail »,
  `App.tsx:412-419`). Sélectionner un résultat ne démonte **pas** le champ de recherche.
- **Mobile** : la `Sheet` n'affiche qu'un seul contenu à la fois, `{ficheBody ?? linePicker}`
  (`App.tsx:407`) — dès qu'une station est sélectionnée, `linePicker` (et son champ de recherche)
  disparaît du DOM, remplacé par la fiche station.

C'est la même classe de défaut que celui documenté sur `followTrainFromPanel` (CLAUDE.md,
limitations) : un élément focalisé retiré du DOM laisse le focus retomber sur `body`. Contrairement
à `followTrainFromPanel`, ce chantier introduit l'interaction : la laisser sans réponse serait
créer sciemment une nouvelle occurrence du même défaut, pas juste hériter de l'existant.

Réponse retenue, symétrique à `closeStation` (`App.tsx:274-277`) qui renvoie déjà le focus au
canevas MapLibre par la même nécessité (pas de cible plus pertinente au moment du démontage) :

```ts
const selectStationFromSearch = (id: string, coords: [number, number]) => {
  selectStation(id, coords);
  // Le champ de recherche est démonté avec le reste de LinePicker dès qu'une station est
  // sélectionnée en mobile (Sheet à contenu unique) : sans retour explicite, le focus
  // retomberait sur `body`, même défaut que closeStation corrige à la fermeture.
  if (isNarrow) {
    focusMap();
  }
};
```

En large (desktop), le focus reste sur le champ — le composant survit, et rester dessus permet
d'enchaîner une deuxième recherche sans revenir dessus au clavier.

## 7. Filet de tests

**Backend**
- `StationSearchTest` (unitaire, sans Spring) : « chatelet » trouve « Châtelet », casse
  indifférente, chaîne vide rend une liste vide, `limit` est respecté, une saisie sans résultat rend
  une liste vide plutôt qu'une exception.
- Le constructeur à 6 arguments de `Station` reste couvert par les 5 tests existants (aucun n'a
  besoin de changer) ; un cas ajouté vérifie que `normalizedName` est bien dérivé (`Station("ST",
  "Châtelet", ...).normalizedName()` vaut `"chatelet"`).
- `StationsControllerIT` (ou extension de l'IT existant) : `GET /stations/search?q=...` rend bien
  le contrat `StationSearchResponse`, couvert par le rate limiter au même titre que les autres
  endpoints (pas de test dédié : `RateLimitIT` teste déjà l'interceptor globalement).

**Frontend**
- `StationSearch.test.tsx` : frappe → résultats affichés (mock de `searchStations`), navigation
  clavier (flèche bas déplace `aria-activedescendant`, Entrée sélectionne, Échap referme),
  sélection appelle `onSelectStation` avec les bons arguments. `axe-core` comme les autres
  composants (`src/test/axe.ts`) — sans attente sur le contraste, jsdom ne l'évalue pas (limitation
  déjà connue, QUA-3/UX-4).
- Pas de test sur le focus post-sélection (`focusMap`) : comme pour `closeStation`, jsdom ne
  distingue pas un vrai changement de focus visuel d'un autre — c'est un point de recette
  navigateur, au même titre que les quatre points listés en § 10 de la spec UX-4.

## 8. Hors périmètre, et pourquoi

- **Tri par pertinence** (préfixe avant sous-chaîne, proximité géographique…) : tranché en § 2,
  l'ordre du registre suffit pour ~321 noms.
- **Recherche floue / tolérante aux fautes de frappe** : tranché en § 2, complexité de réglage
  disproportionnée.
- **Recherche par ligne** (taper « 13 » pour lister les stations de la ligne 13) : une piste
  distincte, pas dans le périmètre de « recherche de station ».
- **Correction du défaut de focus sur `followTrainFromPanel`** : préexistant, documenté, sans lien
  avec ce chantier — seule la nouvelle interaction (§ 6) est traitée ici.
- **Permalien, géolocalisation, sens des tracés, plus de passages** : les quatre autres moitiés
  d'UX-5 (UX-5b à UX-5e), chacune une spec à part si elle est attaquée.

## 9. Ordre d'exécution

1. Backend : `StationSearch` (TDD, unitaire pur) → endpoint `StationsController` → IT.
2. Frontend : extraction de `selectStation` dans `App.tsx` (refactor à froid, aucun changement de
   comportement, filet de tests existant comme garde) → `api/network.ts` + `api/types.ts` →
   `StationSearch.tsx` (TDD) → câblage dans `LinePicker`/`App.tsx`.
3. Recette navigateur : les points que jsdom ne peut pas voir — focus visuel après sélection en
   mobile ET en desktop, annonce lecteur d'écran du `aria-live`, rendu de l'`input` (police/couleur
   héritées ou non, cf. § 5).
