# Stabilité visuelle des véhicules (snap des sauts) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Empêcher le tween d'animer un saut de position invraisemblable entre deux polls : au-delà d'un seuil de distance, on place le train directement (snap) au lieu de le faire glisser.

**Architecture:** Une garde de distance dans `VehicleLayer.update()`. Si la nouvelle position est trop loin de la position affichée (un métro ne parcourt pas des centaines de mètres en un poll), on règle `from = to` (pas d'animation). Sinon, tween normal. Boucle rAF, suivi caméra, couches flèches/halo inchangés.

**Tech Stack:** React / TypeScript / MapLibre GL (frontend).

## Global Constraints

- Frontend SANS tests unitaires (convention projet) → vérif = `cd frontend && npm run build` + contrôle visuel utilisateur.
- Ne toucher QUE la logique de `VehicleLayer.update()` + ajout d'un helper/constante en tête de module. Ne pas modifier la boucle rAF (`startLoop`), le suivi caméra (`jumpTo`), les couches, la sélection.
- Ne jamais masquer un train (décision produit ferme). Le snap n'enlève rien, il supprime seulement l'animation trompeuse.
- Commit en français, préfixe conventionnel, trailer `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`.

---

## Task 1: Snap des sauts invraisemblables dans `VehicleLayer.update()`

**Files:**
- Modify: `frontend/src/map/VehicleLayer.ts`

**Interfaces:**
- Produces: constante module `SNAP_DISTANCE_M = 300` ; fonction pure module `distanceMeters(a: [number, number], b: [number, number]): number` (mètres, approximation équirectangulaire) ; `update()` snappe (`from = to`) quand `distanceMeters(current, target) > SNAP_DISTANCE_M`.

- [ ] **Step 1: Ajouter la constante et le helper en tête de module**

Dans `VehicleLayer.ts`, après les `import` et avant `type V = ...` (ou avant la déclaration de la classe), ajouter :

```typescript
// Au-delà de cette distance entre deux polls, ce n'est pas un déplacement réel de métro
// (~quelques dizaines de mètres par poll) mais une correction/flip de données : on place
// le train directement (snap) au lieu d'animer un glissement trompeur à travers la carte.
const SNAP_DISTANCE_M = 300;

// Distance approximative entre deux [lng, lat] en mètres (équirectangulaire avec cos(lat)),
// suffisante à l'échelle d'une ligne parisienne.
function distanceMeters(a: [number, number], b: [number, number]): number {
  const R = 6371000;
  const rad = Math.PI / 180;
  const dLat = (b[1] - a[1]) * rad;
  const dLng = (b[0] - a[0]) * rad;
  const meanLat = ((a[1] + b[1]) / 2) * rad;
  const x = dLng * Math.cos(meanLat);
  return R * Math.sqrt(x * x + dLat * dLat);
}
```

- [ ] **Step 2: Snapper dans `update()`**

Dans la méthode `update()`, remplacer le bloc qui construit l'anim :

```typescript
      const prev = this.anims.get(vehicle.tripId);
      const current = prev ? this.pointAt(prev, now) : ([vehicle.lng, vehicle.lat] as [number, number]);
      this.anims.set(vehicle.tripId, {
        from: current,
        to: [vehicle.lng, vehicle.lat],
        bearing: vehicle.bearing,
        start: now,
        vehicle,
      });
```

par :

```typescript
      const prev = this.anims.get(vehicle.tripId);
      const current = prev ? this.pointAt(prev, now) : ([vehicle.lng, vehicle.lat] as [number, number]);
      const target: [number, number] = [vehicle.lng, vehicle.lat];
      // Saut invraisemblable → snap (pas d'animation) : from = target. Sinon, tween normal.
      const from = distanceMeters(current, target) > SNAP_DISTANCE_M ? target : current;
      this.anims.set(vehicle.tripId, {
        from,
        to: target,
        bearing: vehicle.bearing,
        start: now,
        vehicle,
      });
```

- [ ] **Step 3: Build**

Run: `cd frontend && npm run build`
Expected: build OK, aucune erreur TypeScript.

- [ ] **Step 4: Contrôle visuel (utilisateur)**

Un métro dont le prochain arrêt saute d'un poll à l'autre ne traverse plus l'écran (il se replace directement) ; les métros qui avancent normalement glissent toujours en douceur.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/map/VehicleLayer.ts
git commit -m "fix(front): snap des sauts de position invraisemblables (plus d'animation trompeuse)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```
