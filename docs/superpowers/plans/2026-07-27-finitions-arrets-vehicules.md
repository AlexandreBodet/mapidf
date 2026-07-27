# Finitions arrêts/véhicules (labels cliquables, dézoom, véhicules concernés) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Trois finitions UX validées : (1) rendre le label de station cliquable comme le rond ; (3) masquer les ronds d'arrêt quand on dézoome beaucoup ; (2) mettre en valeur d'un anneau les véhicules concernés par les prochains passages de l'arrêt ouvert.

**Architecture:** Frontend uniquement. #1/#3 = réglages de couches et handlers dans `useLineShape` + `App`. #2 = `VehicleLayer` porte un ensemble de tripIds « highlighted » (dérivé des `journeyRef` des passages affichés) et dessine un anneau sur ces véhicules ; câblé via `useVehicles` et `App`.

**Tech Stack:** React / TypeScript / MapLibre GL.

## Global Constraints

- Frontend SANS tests unitaires (convention projet) → vérif = `cd frontend && npm run build` + contrôle visuel utilisateur (ne PAS lancer `npm run dev`/Docker).
- Ne pas aggraver les perfs : aucune couche/image reconstruite par frame ; l'anneau `vehicles-highlight` lit la même source `vehicles` déjà mise à jour par frame (une propriété `highlighted` de plus par feature).
- Exclusion mutuelle inchangée : quand une station est ouverte il n'y a pas de train sélectionné (donc halo bleu de sélection et anneau de highlight ne coexistent pas).
- Commit en français, préfixe conventionnel, trailer `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`.

---

## Task 1: Labels cliquables (#1) + masquer les ronds au dézoom (#3)

**Files:**
- Modify: `frontend/src/map/useLineShape.ts`
- Modify: `frontend/src/App.tsx`

**Interfaces:**
- Consumes: couche `stops` (source `stops`, features avec propriété `id`), couche `stops-labels` (même source), handler `onStationClick` déjà défini dans `App`.
- Produces: couche `stops` avec `minzoom: 11` ; couche `stops-labels` cliquable (curseur + clic → `onStationClick`).

- [ ] **Step 1: `useLineShape` — minzoom sur les ronds + curseur sur les labels**

Dans `useLineShape.ts`, ajouter `minzoom: 11` à la couche `stops` (les ronds disparaissent quand on dézoome sous 11 ; l'anneau `stops-selected` n'a pas de minzoom, l'arrêt suivi reste visible). Remplacer le bloc `map.addLayer({ id: "stops", ... })` par :

```typescript
        map.addLayer({
          id: "stops",
          type: "circle",
          source: "stops",
          minzoom: 11,
          paint: {
            "circle-radius": 5,
            "circle-color": "#fff",
            "circle-stroke-color": shape.color,
            "circle-stroke-width": 2,
          },
        });
```

Puis, dans le bloc des curseurs (après les handlers `mouseenter`/`mouseleave` sur `"stops"`), ajouter les mêmes pour `"stops-labels"` :

```typescript
        map.on("mouseenter", "stops-labels", () => { map.getCanvas().style.cursor = "pointer"; });
        map.on("mouseleave", "stops-labels", () => { map.getCanvas().style.cursor = ""; });
```

- [ ] **Step 2: `App` — clic aussi sur les labels**

Dans `App.tsx`, effet de clic : après `map.on("click", "stops", onStationClick);`, enregistrer le même handler sur les labels :

```typescript
    map.on("click", "stops-labels", onStationClick);
```

et dans le `return` de cet effet, ajouter le retrait correspondant, à côté de `map.off("click", "stops", onStationClick);` :

```typescript
      map.off("click", "stops-labels", onStationClick);
```

- [ ] **Step 3: Build**

Run: `cd frontend && npm run build`
Expected: build OK.

- [ ] **Step 4: Contrôle visuel (utilisateur)**

Cliquer sur le NOM d'une station ouvre son panneau passages (comme le rond) ; en dézoomant fortement, les ronds blancs disparaissent (seul le tracé, et l'anneau d'un arrêt sélectionné, restent).

- [ ] **Step 5: Commit**

```bash
git add frontend/src/map/useLineShape.ts frontend/src/App.tsx
git commit -m "feat(front): labels de station cliquables + ronds masqués au dézoom

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 2: Anneau sur les véhicules concernés par les passages (#2)

**Files:**
- Modify: `frontend/src/map/VehicleLayer.ts`
- Modify: `frontend/src/map/useVehicles.ts`
- Modify: `frontend/src/App.tsx`

**Interfaces:**
- Consumes: `station.directions[].passages[].journeyRef` (déjà présent), l'identité `journeyRef === tripId` (vérifiée : mêmes valeurs issues de `journey.journeyRef()`).
- Produces: `VehicleLayer.setHighlighted(ids: Set<string>)` ; couche `vehicles-highlight` (anneau sombre sur les véhicules dont le `tripId ∈ ids`) ; `useVehicles(..., highlightedTripIds)` (8ᵉ paramètre) ; `App` dérive l'ensemble des `journeyRef` de la station ouverte et le passe.

- [ ] **Step 1: `VehicleLayer` — ensemble highlighted + couche anneau + propriété feature**

Dans `VehicleLayer.ts` :

(a) Ajouter le champ (après `private follow = false;`) :

```typescript
  private highlightedTripIds: Set<string> = new Set();
```

(b) Ajouter la méthode (après `setFollow`) :

```typescript
  setHighlighted(ids: Set<string>) {
    this.highlightedTripIds = ids;
  }
```

(c) Dans `ensureLayer().add`, APRÈS l'ajout de la couche `vehicles-halo` et AVANT la couche `vehicles` (l'anneau reste sous les flèches), ajouter :

```typescript
      // Anneau sur les véhicules concernés par les passages de l'arrêt ouvert (distinct
      // du halo bleu de sélection). Filtré sur la propriété `highlighted` des features.
      this.map.addLayer({
        id: "vehicles-highlight",
        type: "circle",
        source: "vehicles",
        filter: ["==", ["get", "highlighted"], true],
        paint: {
          "circle-radius": 11,
          "circle-color": "rgba(0,0,0,0)",
          "circle-stroke-color": "#111",
          "circle-stroke-width": 2.5,
        },
      });
```

(d) Dans `startLoop().step`, ajouter la propriété `highlighted` aux features (à côté de `selected`) :

```typescript
          const selected = anim.vehicle.tripId === this.selectedTripId;
          const highlighted = this.highlightedTripIds.has(anim.vehicle.tripId);
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
              expectedTime: anim.vehicle.expectedTime,
              status: anim.vehicle.status,
              selected,
              highlighted,
            },
            geometry: { type: "Point", coordinates: [lng, lat] },
          } as GeoJSON.Feature;
```

- [ ] **Step 2: `useVehicles` — 8ᵉ paramètre + effet de mise à jour**

Dans `useVehicles.ts`, ajouter le paramètre en fin de signature :

```typescript
  onCount?: (n: number) => void,
  highlightedTripIds: Set<string> = new Set(),
) {
```

et un effet (après l'effet `setColor`) :

```typescript
  useEffect(() => {
    layerRef.current?.setHighlighted(highlightedTripIds);
  }, [map, lineId, highlightedTripIds]);
```

- [ ] **Step 3: `App` — dériver l'ensemble des journeyRef et le passer**

Dans `App.tsx` :

(a) Importer `useMemo` :

```typescript
import { useEffect, useMemo, useRef, useState } from "react";
```

(b) Dériver l'ensemble (après la déclaration des états, avant `useLineShape`) :

```typescript
  // Trains concernés par les passages de la station ouverte (surlignés sur la carte).
  const highlightedTripIds = useMemo(
    () => new Set(station?.directions.flatMap((d) => d.passages.map((p) => p.journeyRef)) ?? []),
    [station],
  );
```

(c) Passer l'ensemble en 8ᵉ argument de `useVehicles` :

```typescript
  useVehicles(map, LINE_ID, lineColor, selectedTripId, follow, (v) => {
    if (v) {
      setSelected(toSelected(v));
    }
  }, setCount, highlightedTripIds);
```

- [ ] **Step 4: Build**

Run: `cd frontend && npm run build`
Expected: build OK.

- [ ] **Step 5: Contrôle visuel (utilisateur)**

Cliquer un arrêt : les métros qui y passent (ceux listés dans le panneau) portent un anneau sombre ; fermer le panneau (✕, clic véhicule, ou suivre un passage) fait disparaître les anneaux ; l'anneau se met à jour au rafraîchissement du panneau.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/map/VehicleLayer.ts frontend/src/map/useVehicles.ts frontend/src/App.tsx
git commit -m "feat(front): anneau sur les véhicules concernés par les passages de l'arrêt ouvert

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```
