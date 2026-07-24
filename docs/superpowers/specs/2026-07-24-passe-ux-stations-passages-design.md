# Passe UX : stations regroupées, prochains passages, véhicules directionnels

Date : 2026-07-24
Statut : validé (design), prêt pour le plan d'implémentation

## Contexte

MVP mono-ligne (métro ligne 9) fonctionnel. Avant d'attaquer le multi-ligne, on
solidifie l'UX/UI. Positions **estimées** par interpolation (pas de GPS en métro).
Cette passe reste **mono-ligne** ; aucun choix ne doit fermer la porte au multi-ligne.

## ⚠️ Prérequis de scalabilité à traiter AU passage multi-ligne (dette connue)

> **À re-signaler explicitement quand on ouvrira le chantier multi-ligne.**

La boucle `requestAnimationFrame` de `VehicleLayer` **reconstruit toute la
`FeatureCollection` et appelle `setData` à chaque frame**. À ~85 véhicules (une ligne)
c'est indolore ; à plusieurs milliers (tout le réseau), ce rebuild par frame devient le
**goulot d'étranglement n°1**. Cette passe ne le refactore pas (hors périmètre) mais
**ne l'aggrave pas**. À traiter avant le multi-ligne. Pistes : throttle du tween,
`feature-state` au lieu d'un `setData` complet, diff des features, ne tweener que les
véhicules visibles dans le viewport.

## Périmètre

Inclus :
1. Stations regroupées par `parent_station` (une station, pas deux quais).
2. Noms de stations affichés au-delà d'un seuil de zoom.
3. Prochains passages à un arrêt (clic → panneau).
4. Véhicules directionnels (flèche orientée) à la couleur de la ligne.
5. Gains rapides : bouton nord, curseur `pointer`, croix ✕ agrandie, légende + compteur.

Exclus (repoussés) :
- Mise en valeur de la ligne sélectionnée → **multi-ligne** (pas de contraste en mono-ligne).
- Tableau de passages multi-ligne à un arrêt → **multi-ligne**.
- Refactor perf de la boucle rAF → **prérequis multi-ligne** (voir ci-dessus).

## Backend

### Stations regroupées par `parent_station`

- **Schéma** : colonne `parent_station` (nullable) sur `stop`, via migration Flyway
  (`db/migration`). Champ `parentStation` sur l'entité `Stop`.
- **Loader** (`GtfsStaticLoader.persistStops`) : lire `parent_station` de `stops.txt`
  via le helper `safe` (→ `null` si absent). On ne persiste pas les StopArea séparément.
- **Requête** (`NetworkQueryService.getShape`) : regrouper les quais de la ligne par
  **clé de station** = `parentStation` si présent, sinon le `gtfsId` du quai (fallback
  qui gère les arrêts à sens unique, ex. ligne 10). Par groupe : `id` = clé de station,
  `name` = nom d'un membre, `lat`/`lng` = **centroïde des quais membres**,
  `platformIds` = liste des `gtfsId` des quais.

### Contrat `/shape`

`StopDto` devient une **station** : `{ id, name, lat, lng, platformIds: string[] }`.
Un seul point par station. `platformIds` sert l'appel « prochains passages ».

### Endpoint « prochains passages »

- **Approche** : agrégation **côté backend** depuis le snapshot temps réel déjà en
  mémoire — pas d'appel PRIM, pas de nouvelle dépendance. Rejeté : `stop-monitoring`
  PRIM (appel réseau + quota inutiles, la donnée est déjà là).
- **Route** : `GET /api/lines/{lineId}/stations/{stationId}/departures`.
- **Logique** : `stationId` → `platformIds` → `stopKey`s (via `PositionEngine.stopKey`,
  qui strippe les non-chiffres : SIRI `STIF:StopPoint:Q:463641:` et GTFS `IDFM:463641`
  → `463641`). Parcourir les `LiveJourney` de la ligne, retenir les `Call` dont le
  `stopKey` ∈ ceux de la station et l'heure **future**. **Grouper par destination**
  (terminus de la course), trier par heure, **garder 3 par direction**.
- **Réponse** : `{ stationName, directions: [{ destination, passages:
  [{ expectedTime, status }] }] }`. L'ETA (« dans X min ») est calculée côté front.
- **Scale (préparé, pas fait)** : à l'ingest global, remplacer le scan par un index
  `Map<stopKey, List<Call>>` **construit une fois par rafraîchissement du snapshot**
  (dans le parse, coût déjà payé) → lookup O(1). L'endpoint est conçu pour brancher cet
  index **sans changer le contrat**. Un tableau multi-ligne ajoutera un niveau `line`
  dans `directions[]`.

### Tests backend (TDD)

- Loader : `parent_station` lu et persisté (+ cas absent → `null`).
- `getShape` : deux quais même parent → une station, centroïde correct, `platformIds`
  complet ; quai sans parent → station seule.
- Departures : agrégation par direction, tri, cap à 3, exclusion des passages passés.
  **Station inconnue → 404** via `ApiException` + nouvel `ErrorCode.STATION_NOT_FOUND`
  (aligné sur le pattern existant `LINE_NOT_FOUND`). Station connue mais aucun passage à
  venir → **200 avec `directions: []`** (pas une erreur).

## Frontend

### Stations (marqueurs regroupés + noms + clic)

- `useLineShape` consomme une station par point (fusion faite côté backend). Couche
  `stops` (cercle) : un cercle par station.
- **Noms** : couche `symbol` `stops-labels` (`text-field` = nom), `minzoom` ~13, collision
  native MapLibre → pas d'encombrement au dézoom, coût maîtrisé même à grand nombre.
- **Clic** : handler sur la couche `stops` → fetch departures → ouvre le panneau arrêt
  et **désélectionne le train** (exclusivité mutuelle). Réciproquement, cliquer un train
  ferme le panneau arrêt.

### Panneau « prochains passages »

- Composant `StopPanel`, même emplacement haut-droite que `VehiclePanel` (**un seul
  visible à la fois**). Titre = nom station ; sections `→ {destination}` ; 3 passages
  « dans X min » (réutiliser `formatEta`). Croix ✕ pour fermer.
- `App.tsx` : état `selectedStation`, **exclusif** avec `selectedTripId` (sélectionner
  l'un vide l'autre).

### Véhicules directionnels + couleur de ligne

- `VehicleLayer` : couche `symbol` avec **icône flèche SDF générée une seule fois**
  (`addImage` au montage, jamais par update), `icon-rotate: ["get","bearing"]`,
  `icon-rotation-alignment: "map"` → la flèche pointe dans le sens de marche.
- **Couleur** = couleur de la ligne (passée depuis `/shape`, `icon-color`).
- **État sélectionné** : halo = couche `circle` sous les flèches, rendue uniquement pour
  la feature `selected` (anneau bleu actuel). Suivi/`jumpTo`/relâche inchangés.
- **Perf** : seule la source `vehicles` est mise à jour par frame (comme aujourd'hui) ;
  flèche, halo et labels sont des couches statiques non reconstruites par frame.

### Gains rapides

- **Bouton nord** : `NavigationControl` MapLibre (boussole, remet cap+inclinaison à 0),
  placé **en haut-gauche** (ne chevauche pas le panneau haut-droite).
- **Curseur `pointer`** : `mouseenter`/`mouseleave` sur les couches `vehicles` et `stops`.
- **Croix ✕ agrandie** : zone de clic et police augmentées dans les panneaux.
- **Légende + compteur** : encart fixe **en bas-gauche** — pastille couleur ligne +
  « position estimée (pas de GPS) » + « **N trains en circulation** » (N = nb de
  véhicules du dernier poll).

### Vérification frontend

Pas de tests unitaires front (comme le reste du projet) → `npm run build` + contrôle
visuel utilisateur. La logique nouvelle est couverte côté backend (tests ci-dessus).

## Découpage en unités

- **Backend** : (a) migration + entité `Stop.parentStation` ; (b) loader lit
  `parent_station` ; (c) `getShape` regroupe → `StopDto` station ; (d) service + endpoint
  departures.
- **Frontend** : (e) `/shape` stations + labels + clic ; (f) `StopPanel` + état exclusif ;
  (g) `VehicleLayer` flèches + couleur + halo ; (h) gains rapides (contrôle, curseur, ✕,
  légende/compteur).

Chaque unité est testable/validable indépendamment ; le contrat `/shape` est le seul point
de couplage backend→frontend qui change.
