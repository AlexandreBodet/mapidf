# Suivi & surlignage du train sélectionné — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Au clic sur un train, le surligner sur la carte et suivre sa position à la caméra (suivi doux qui se relâche à la manipulation, réactivable par bouton ou re-clic).

**Architecture:** Frontend uniquement. L'état `selectedTripId` + `follow` vit dans `App`. `VehicleLayer` (déjà responsable du tween rAF des positions) gère le style de surlignage via une propriété `selected` par feature et recentre la carte (`jumpTo`) sur la position interpolée du train suivi. `useVehicles` relaie ces valeurs au layer sans jamais le recréer (layer conservé dans un ref, effets de synchro séparés). La relâche auto détecte les gestes utilisateur via `originalEvent` sur `movestart`.

**Tech Stack:** React 18, TypeScript, MapLibre GL JS 4.7.1, Vite.

## Global Constraints

- Aucun changement backend ; contrat `/vehicles` inchangé.
- Un seul train sélectionné/suivi à la fois (YAGNI).
- Le zoom courant est conservé pendant le suivi (`jumpTo`, jamais de zoom auto).
- Pas de framework de test frontend dans le projet : la vérification de chaque tâche
  est `npm run build` (typecheck TS + build Vite) exécuté depuis `frontend/`.
  Aucune app n'est lancée par l'agent (l'utilisateur gère backend/front/Docker) ;
  la validation comportementale visuelle est faite par l'utilisateur.
- Les nouveaux paramètres/props sont introduits **optionnels** (valeur par défaut) pour
  que le build reste vert après chaque tâche ; `App` (dernière tâche) branche les vraies valeurs.

## Fichiers touchés

- Modifier `frontend/src/map/VehicleLayer.ts` — surlignage + suivi caméra (Task 1).
- Modifier `frontend/src/map/useVehicles.ts` — relais `selectedTripId`/`follow` au layer (Task 2).
- Modifier `frontend/src/ui/VehiclePanel.tsx` — bouton « Suivre » (Task 3).
- Modifier `frontend/src/App.tsx` — état, clic, relâche auto, câblage (Task 4).

---

### Task 1: VehicleLayer — surlignage + suivi caméra

**Files:**
- Modify: `frontend/src/map/VehicleLayer.ts`

**Interfaces:**
- Consumes: rien de nouveau (méthodes existantes `update`, `pointAt`, `startLoop`).
- Produces (utilisées par Task 2) :
  - `VehicleLayer.setSelected(tripId: string | null): void`
  - `VehicleLayer.setFollow(follow: boolean): void`
  - chaque feature véhicule expose désormais une propriété booléenne `selected`.

- [ ] **Step 1: Ajouter les champs d'état de sélection/suivi**

Dans `frontend/src/map/VehicleLayer.ts`, ajouter deux champs privés à la classe, juste après `private cancelReady`.

Remplacer :
```ts
export class VehicleLayer {
  private anims = new Map<string, Anim>();
  private raf = 0;
  private cancelReady: (() => void) | null = null;
```
par :
```ts
export class VehicleLayer {
  private anims = new Map<string, Anim>();
  private raf = 0;
  private cancelReady: (() => void) | null = null;
  private selectedTripId: string | null = null;
  private follow = false;
```

- [ ] **Step 2: Ajouter les setters `setSelected` / `setFollow`**

Dans la même classe, ajouter ces deux méthodes juste après le constructeur (avant `private ensureLayer()`) :
```ts
  setSelected(tripId: string | null) {
    this.selectedTripId = tripId;
  }

  setFollow(follow: boolean) {
    this.follow = follow;
  }
```

- [ ] **Step 3: Style de surlignage dans le paint du layer**

Dans `ensureLayer()`, remplacer le bloc `paint` de `addLayer` par une version où rayon,
couleur de contour et épaisseur de contour dépendent de la propriété `selected` :

Remplacer :
```ts
        paint: {
          "circle-radius": 7,
          "circle-color": ["case", ["==", ["get", "source"], "REALTIME"], "#e30613", "#f7a600"],
          "circle-stroke-color": "#fff",
          "circle-stroke-width": 2,
          "circle-opacity": ["case", ["==", ["get", "source"], "INTERPOLATED"], 0.7, 1.0],
        },
```
par :
```ts
        paint: {
          "circle-radius": ["case", ["get", "selected"], 11, 7],
          "circle-color": ["case", ["==", ["get", "source"], "REALTIME"], "#e30613", "#f7a600"],
          "circle-stroke-color": ["case", ["get", "selected"], "#1d4ed8", "#fff"],
          "circle-stroke-width": ["case", ["get", "selected"], 4, 2],
          "circle-opacity": ["case", ["==", ["get", "source"], "INTERPOLATED"], 0.7, 1.0],
        },
```

- [ ] **Step 4: Exposer `selected` par feature + recentrer sur le train suivi**

Dans `startLoop()`, remplacer le corps de `step` (le bloc `if (source) { ... }`) pour
(a) calculer `selected` par feature, (b) mémoriser la position interpolée du train suivi,
(c) faire `jumpTo` dessus après `setData`.

Remplacer :
```ts
      const source = this.map.getSource("vehicles") as GeoJSONSource | undefined;
      if (source) {
        const features = [...this.anims.values()].map((anim) => {
          const [lng, lat] = this.pointAt(anim, now);
          return {
            type: "Feature",
            properties: {
              tripId: anim.vehicle.tripId,
              source: anim.vehicle.source,
              bearing: anim.bearing,
              headsign: anim.vehicle.headsign,
              nextStop: anim.vehicle.nextStop,
              status: anim.vehicle.status,
            },
            geometry: { type: "Point", coordinates: [lng, lat] },
          } as GeoJSON.Feature;
        });
        source.setData(this.featureCollection(features));
      }
```
par :
```ts
      const source = this.map.getSource("vehicles") as GeoJSONSource | undefined;
      if (source) {
        let followPoint: [number, number] | null = null;
        const features = [...this.anims.values()].map((anim) => {
          const [lng, lat] = this.pointAt(anim, now);
          const selected = anim.vehicle.tripId === this.selectedTripId;
          if (selected && this.follow) {
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
              status: anim.vehicle.status,
              selected,
            },
            geometry: { type: "Point", coordinates: [lng, lat] },
          } as GeoJSON.Feature;
        });
        source.setData(this.featureCollection(features));
        if (followPoint) {
          this.map.jumpTo({ center: followPoint });
        }
      }
```

- [ ] **Step 5: Vérifier le build**

Run: `cd frontend && npm run build`
Expected: build réussi (aucune erreur TS). Le layer compile ; `setSelected`/`setFollow`
ne sont pas encore appelés — c'est attendu.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/map/VehicleLayer.ts
git commit -m "feat(frontend): surlignage + suivi caméra dans VehicleLayer

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: useVehicles — relayer sélection/suivi au layer

**Files:**
- Modify: `frontend/src/map/useVehicles.ts`

**Interfaces:**
- Consumes (de Task 1) : `VehicleLayer.setSelected`, `VehicleLayer.setFollow`.
- Produces (utilisée par Task 4) :
  - `useVehicles(map: MlMap | null, lineId: string, selectedTripId?: string | null, follow?: boolean): void`

- [ ] **Step 1: Réécrire le hook avec un ref sur le layer + effets de synchro**

Remplacer **tout** le contenu de `frontend/src/map/useVehicles.ts` par :
```ts
import { useEffect, useRef } from "react";
import type { Map as MlMap } from "maplibre-gl";
import { fetchVehicles } from "../api/lines";
import { VEHICLE_POLL_MS } from "../api/config";
import { VehicleLayer } from "./VehicleLayer";

export function useVehicles(
  map: MlMap | null,
  lineId: string,
  selectedTripId: string | null = null,
  follow = false,
) {
  const layerRef = useRef<VehicleLayer | null>(null);

  useEffect(() => {
    if (!map) {
      return;
    }
    const layer = new VehicleLayer(map, VEHICLE_POLL_MS);
    layerRef.current = layer;
    let cancelled = false;
    let timer: number;
    const tick = async () => {
      try {
        const response = await fetchVehicles(lineId);
        if (cancelled) {
          return;
        }
        layer.update(response.vehicles, performance.now());
      } catch {
        // on conserve l'affichage courant
      }
      if (cancelled) {
        return;
      }
      timer = window.setTimeout(tick, VEHICLE_POLL_MS);
    };
    tick();
    return () => {
      cancelled = true;
      window.clearTimeout(timer);
      layer.destroy();
      layerRef.current = null;
    };
  }, [map, lineId]);

  useEffect(() => {
    layerRef.current?.setSelected(selectedTripId);
  }, [map, lineId, selectedTripId]);

  useEffect(() => {
    layerRef.current?.setFollow(follow);
  }, [map, lineId, follow]);
}
```

Note : `map`/`lineId` sont inclus dans les deps des effets de synchro pour ré-appliquer
la sélection/le suivi si le layer est recréé (changement de carte ou de ligne).

- [ ] **Step 2: Vérifier le build**

Run: `cd frontend && npm run build`
Expected: build réussi. `App.tsx` appelle encore `useVehicles(map, LINE_ID)` (2 args) —
compile grâce aux paramètres optionnels.

- [ ] **Step 3: Commit**

```bash
git add frontend/src/map/useVehicles.ts
git commit -m "feat(frontend): useVehicles relaie sélection/suivi au layer

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: VehiclePanel — bouton « Suivre »

**Files:**
- Modify: `frontend/src/ui/VehiclePanel.tsx`

**Interfaces:**
- Produces (utilisées par Task 4) : props additionnelles optionnelles
  `following?: boolean` et `onFollow?: () => void` sur `VehiclePanel`.

- [ ] **Step 1: Étendre les props**

Dans `frontend/src/ui/VehiclePanel.tsx`, remplacer l'interface `Props` :
```ts
interface Props {
  vehicle: { headsign: string; nextStop: string; status: string; source: string } | null;
  onClose: () => void;
}
```
par :
```ts
interface Props {
  vehicle: { headsign: string; nextStop: string; status: string; source: string } | null;
  following?: boolean;
  onFollow?: () => void;
  onClose: () => void;
}
```

- [ ] **Step 2: Déstructurer les nouvelles props**

Remplacer la signature du composant :
```ts
export function VehiclePanel({ vehicle, onClose }: Props) {
```
par :
```ts
export function VehiclePanel({ vehicle, following = false, onFollow, onClose }: Props) {
```

- [ ] **Step 3: Ajouter le bouton « Suivre »**

Ajouter le bouton juste après le paragraphe « Position » (avant la fermeture `</div>`).

Remplacer :
```ts
      <p style={{ margin: "4px 0", color: "#666" }}>
        Position : {vehicle.source === "REALTIME" ? "GPS temps réel" : "estimée (horaire)"}
      </p>
    </div>
```
par :
```ts
      <p style={{ margin: "4px 0", color: "#666" }}>
        Position : {vehicle.source === "REALTIME" ? "GPS temps réel" : "estimée (horaire)"}
      </p>
      <button
        onClick={onFollow}
        style={{
          marginTop: 8,
          padding: "6px 12px",
          border: "1px solid #1d4ed8",
          borderRadius: 6,
          cursor: "pointer",
          background: following ? "#1d4ed8" : "#fff",
          color: following ? "#fff" : "#1d4ed8",
          font: "13px sans-serif",
        }}
      >
        {following ? "◉ Suivi actif" : "◉ Suivre"}
      </button>
    </div>
```

- [ ] **Step 4: Vérifier le build**

Run: `cd frontend && npm run build`
Expected: build réussi. `App.tsx` rend `<VehiclePanel vehicle={selected} onClose={...} />`
sans les nouvelles props — compile car elles sont optionnelles.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/ui/VehiclePanel.tsx
git commit -m "feat(frontend): bouton Suivre dans VehiclePanel

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 4: App — état, clic, relâche auto, câblage

**Files:**
- Modify: `frontend/src/App.tsx`

**Interfaces:**
- Consumes : `useVehicles(map, lineId, selectedTripId, follow)` (Task 2) ;
  `VehiclePanel` props `following`/`onFollow` (Task 3) ;
  propriété `tripId` des features véhicules (déjà exposée, cf. Task 1).

- [ ] **Step 1: Réécrire App.tsx (état + clic + relâche auto + câblage)**

Remplacer **tout** le contenu de `frontend/src/App.tsx` par :
```tsx
import { useEffect, useRef, useState } from "react";
import maplibregl from "maplibre-gl";
import { useMap } from "./map/MapView";
import { useLineShape } from "./map/useLineShape";
import { useVehicles } from "./map/useVehicles";
import { VehiclePanel } from "./ui/VehiclePanel";
import { LINE_ID } from "./api/config";

type Selected = { headsign: string; nextStop: string; status: string; source: string } | null;

export default function App() {
  const container = useRef<HTMLDivElement>(null);
  const map = useMap(container);
  const [selected, setSelected] = useState<Selected>(null);
  const [selectedTripId, setSelectedTripId] = useState<string | null>(null);
  const [follow, setFollow] = useState(false);
  useLineShape(map, LINE_ID);
  useVehicles(map, LINE_ID, selectedTripId, follow);

  useEffect(() => {
    if (!map) {
      return;
    }
    const onClick = (e: maplibregl.MapLayerMouseEvent) => {
      const props = e.features?.[0]?.properties;
      if (!props) {
        return;
      }
      setSelected(props as Selected);
      setSelectedTripId(props.tripId as string);
      setFollow(true);
    };
    map.on("click", "vehicles", onClick);
    return () => {
      map.off("click", "vehicles", onClick);
    };
  }, [map]);

  useEffect(() => {
    if (!map) {
      return;
    }
    const onMoveStart = (e: maplibregl.MapLibreEvent) => {
      if ((e as { originalEvent?: unknown }).originalEvent) {
        setFollow(false);
      }
    };
    map.on("movestart", onMoveStart);
    return () => {
      map.off("movestart", onMoveStart);
    };
  }, [map]);

  const clearSelection = () => {
    setSelected(null);
    setSelectedTripId(null);
    setFollow(false);
  };

  return (
    <>
      <div ref={container} style={{ position: "absolute", inset: 0 }} />
      <VehiclePanel
        vehicle={selected}
        following={follow}
        onFollow={() => setFollow(true)}
        onClose={clearSelection}
      />
    </>
  );
}
```

Points clés :
- Clic sur un train → mémorise libellés + `tripId` + `follow = true` (couvre aussi le
  re-clic de réactivation).
- `movestart` avec `originalEvent` = geste utilisateur → coupe le suivi. Les `jumpTo`
  internes du layer n'ont pas d'`originalEvent`, donc pas de boucle infinie.
- Bouton « Suivre » → `setFollow(true)` (recentrage au frame suivant).
- Croix → `clearSelection()` remet tout à zéro (plus de surlignage ni de suivi).

- [ ] **Step 2: Vérifier le build**

Run: `cd frontend && npm run build`
Expected: build réussi, feature complète.

- [ ] **Step 3: Commit**

```bash
git add frontend/src/App.tsx
git commit -m "feat(frontend): sélection/suivi du train cliqué (état + relâche auto)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

- [ ] **Step 4: Validation visuelle (par l'utilisateur)**

L'utilisateur lance les apps et vérifie :
1. Clic sur un train → il grossit avec un anneau bleu ; la carte le suit (reste centré).
2. Pan/zoom manuel → le suivi se coupe (le train continue de bouger, carte libre).
3. Bouton « Suivre » (plein quand actif) → recentre et réactive le suivi ; re-clic sur le
   train visible aussi.
4. Croix → plus de surlignage, plus de suivi, panneau fermé.
5. Le niveau de zoom est conservé pendant le suivi.

---

## Self-Review

**Couverture spec :**
- §1 État (selectedTripId, follow, flux clic/re-clic/croix/bouton) → Task 4. ✓
- §2 Surlignage (setSelected, propriété `selected`, paint rayon/contour) → Task 1. ✓
- §3 Suivi caméra (setFollow, jumpTo sur position interpolée) → Task 1 + relais Task 2. ✓
- §4 Relâche auto (movestart + originalEvent) → Task 4. ✓
- §5 Bouton « Suivre » (props following/onFollow, plein/contour) → Task 3 + câblage Task 4. ✓
- §6 Câblage (useVehicles signature étendue, setSelected/setFollow) → Task 2. ✓
- Hors périmètre (1 seul train, pas de zoom auto, pas de stations, pas de backend) → respecté.

**Placeholders :** aucun ; tous les blocs de code sont complets.

**Cohérence des types :** `setSelected(string | null)` / `setFollow(boolean)` définis en Task 1,
appelés à l'identique en Task 2 ; `useVehicles(map, lineId, selectedTripId?, follow?)` défini
Task 2, appelé avec 4 args en Task 4 ; props `following?`/`onFollow?` définies Task 3,
fournies en Task 4 ; propriété feature `selected` (Task 1) lue par le paint (Task 1).
