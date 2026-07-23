# Suivi & surlignage du train sélectionné — design

Date : 2026-07-23
Portée : frontend uniquement (aucun changement backend, contrat `/vehicles` inchangé).

## Objectif

Au clic sur un train de la carte, en plus du panneau de détails déjà affiché en haut
à droite ([VehiclePanel](../../../frontend/src/ui/VehiclePanel.tsx)) :

1. **Surligner** visuellement le train cliqué pour le suivre à l'œil parmi les autres.
2. **Suivre** ce train à la caméra : la carte reste centrée dessus pendant qu'il se déplace.

Le suivi doit rester non intrusif : dès que l'utilisateur manipule la carte (pan/zoom),
le suivi se coupe, et un bouton (ou un re-clic) le réactive.

## Contexte actuel

- Le clic sur un train est géré dans [App.tsx](../../../frontend/src/App.tsx) : il lit
  `e.features[0].properties` et stocke dans `selected` uniquement les libellés
  (`headsign`, `nextStop`, `status`, `source`) — **pas le `tripId`**.
- Les positions des véhicules sont interpolées en continu (tween `requestAnimationFrame`)
  dans [VehicleLayer.ts](../../../frontend/src/map/VehicleLayer.ts) (`pointAt`, `startLoop`).
  Les features exposent déjà `tripId` dans leurs propriétés.
- Le layer est instancié par le hook `useVehicles(map, lineId)`.

Seul vrai manque pour surligner/suivre : conserver l'**identité stable** du train (`tripId`).

## Décisions retenues (brainstorming)

- **Mode suivi** : suivi doux + relâche automatique. La carte reste centrée en continu ;
  toute manipulation manuelle coupe le suivi ; le zoom courant est conservé.
- **Réactivation** : bouton « Suivre » dans le panneau **et** re-clic sur le train.

## Conception

### 1. État (App.tsx)

Deux valeurs supplémentaires, `App` restant propriétaire de l'état :

- `selectedTripId: string | null` — identité stable du train sélectionné.
- `follow: boolean` — suivi caméra actif.

Flux :
- Clic sur un train → renseigne les libellés (`selected`) **+ `selectedTripId`** issu de
  `properties.tripId` **+ `follow = true`**.
- Re-clic sur le train déjà sélectionné → `follow = true` (réactivation).
- Croix (✕) du panneau → réinitialise `selected`, `selectedTripId = null`, `follow = false`.
- Bouton « Suivre » du panneau → `follow = true` (recentrage immédiat).

### 2. Surlignage (VehicleLayer)

- Nouvelle méthode publique `setSelected(tripId: string | null)`.
- Chaque feature reçoit une propriété booléenne `selected = (tripId === selectedTripId)`.
- Style de cercle conditionnel via expressions MapLibre :
  - `circle-radius` : `7` → `11` quand `selected`.
  - `circle-stroke-width` : `2` → `4` quand `selected`.
  - `circle-stroke-color` : contour distinctif quand `selected` (couleur ligne / foncé),
    blanc sinon.

Coût nul : les features sont déjà reconstruites à chaque frame dans `startLoop`.

### 3. Suivi caméra (boucle rAF de VehicleLayer)

- Nouvelle méthode publique `setFollow(follow: boolean)`.
- Dans `startLoop`, après calcul de la position interpolée du train sélectionné : si
  `follow` est actif et que l'`Anim` du `selectedTripId` existe, appeler
  `map.jumpTo({ center: [lng, lat] })` sur cette position interpolée.
- `jumpTo` (et non `easeTo`) : recentrage immédiat sans file d'animation concurrente, et
  **sans toucher au zoom**. Le point bougeant déjà de façon fluide, le suivi est fluide.

### 4. Relâche automatique (App)

- `App` écoute `map.on("movestart", handler)`.
- Si l'événement porte un `originalEvent` (geste **utilisateur** : drag, molette, tactile),
  passer `follow = false`.
- Les `jumpTo` internes du suivi **n'ont pas** d'`originalEvent` → aucune boucle infinie,
  le suivi programmatique ne se coupe pas lui-même.

### 5. Bouton « Suivre » (VehiclePanel)

- Nouvelles props : `following: boolean` et `onFollow: () => void`.
- Bouton « ◉ Suivre » : rendu **plein** quand `following`, **contour** sinon.
- Clic → `onFollow()` (réactive le suivi et recentre). Accessible même si le train est
  hors écran (le panneau reste affiché tant qu'un train est sélectionné).

### 6. Câblage (useVehicles)

- Signature étendue : `useVehicles(map, lineId, selectedTripId, follow)`.
- Des effets poussent les valeurs vers le layer : `layer.setSelected(selectedTripId)` et
  `layer.setFollow(follow)` (sur changement de la valeur correspondante).

## Hors périmètre (YAGNI)

- Pas de suivi de plusieurs trains simultanément (un seul sélectionné).
- Pas de changement de zoom automatique (« zoom to fit ») : on conserve le zoom courant.
- Pas de sélection/suivi des stations physiques (seuls les véhicules sont concernés).
- Aucun changement backend.

## Tests & validation

- Pas de tests unitaires frontend à ce jour dans le projet.
- Validation : `npm run build` (vérification TypeScript), puis validation visuelle par
  l'utilisateur (il lance les apps lui-même — cf. cycle de vie géré côté IntelliJ).
