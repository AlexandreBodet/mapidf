# Stabilité visuelle des véhicules : ne pas animer les sauts invraisemblables

Date : 2026-07-27
Statut : validé (design), prêt pour le plan d'implémentation

## Contexte

Chantier **B** issu des retours visuels sur la passe UX (branche
`feat/passe-ux-stations-passages`). Deux problèmes distincts avaient été identifiés :

- **A — fiabilité du placement** (données SIRI : courses à un seul appel = terminus
  lointain, pas d'arrêts passés) → mauvais placement + ETA aberrante. **Hors périmètre
  ici** : reste ticketé, nécessite un signal de confiance non temporel (jamais un seuil
  d'ETA qui masque). Voir le journal `.superpowers/sdd/progress.md`.
- **B — stabilité visuelle** (le présent document) : l'artefact de rendu.

## Problème

`VehicleLayer.update()` règle, pour chaque train, `from = position affichée`,
`to = nouvelle position`, et la boucle `requestAnimationFrame` interpole (tween ~4 s)
de `from` vers `to`. Quand le backend renvoie une position très différente d'un poll à
l'autre (prochain arrêt qui « flippe » car les `EstimatedCall` ne sont pas triés, ou trou
de données), le tween **anime ce saut** : le train traverse l'écran sur 4 s puis se
replace au poll suivant (symptôme « le métro traverse Paris »), ou oscille (« stop toutes
les 2 s »). Ce n'est pas un problème de données mais de rendu : on glisse sur une
correction qui n'est pas un déplacement réel.

## Décision

Sur un saut **invraisemblable** (distance `from → to` supérieure à un seuil qu'un métro
ne peut pas parcourir en un intervalle de poll), on **snap** : `from = to`, donc pas de
glissement — le train apparaît directement à la position corrigée. Les déplacements
normaux (petits) continuent de glisser en douceur.

Décision produit confirmée : on ne masque jamais un train. Le snap n'enlève rien, il
supprime seulement l'animation trompeuse.

## Conception

Périmètre : uniquement la logique de `VehicleLayer.update()` (frontend). Boucle rAF, suivi
caméra (`jumpTo`), couches flèches/halo, sélection — **inchangés**.

- **`distanceMeters(a, b): number`** — distance approximative entre deux `[lng, lat]`
  (équirectangulaire avec `cos(lat)`), suffisante à l'échelle d'une ligne parisienne.
  Fonction pure, sans état.
- **`SNAP_DISTANCE_M = 300`** — constante nommée, ajustable. Justification : en ~4 s
  (intervalle de poll) un métro parcourt quelques dizaines de mètres ; le tween sert à
  lisser cela. Au-delà de ~300 m, c'est une correction/flip, pas un déplacement réel.
- Dans `update()`, pour chaque véhicule : calculer `current` (position affichée actuelle,
  comme aujourd'hui) puis `distanceMeters(current, [lng, lat])`. Si `> SNAP_DISTANCE_M`
  → `from = to = [lng, lat]` (snap, aucune animation). Sinon → `from = current`,
  `to = [lng, lat]` (tween normal, comportement actuel).
- Premier affichage d'un véhicule (pas d'anim précédente) : `current = [lng, lat]`,
  distance 0 → animation en place (inchangé).

## Effet attendu

- #2 (« traverse Paris puis se replace ») : supprimé — le saut est snappé, plus animé.
- #5 (« stop toutes les 2 s ») : atténué — une oscillation clignote entre deux points au
  lieu de glisser (moins gênant) ; pas éliminée (relève du chantier A).
- Déplacements normaux : toujours fluides.

## Vérification

Pas de tests unitaires front (convention projet) → `npm run build` + contrôle visuel
utilisateur (un métro dont le prochain arrêt saute ne traverse plus l'écran ; les trains
qui avancent normalement glissent toujours).

## Hors périmètre (rappel)

- Chantier A (fiabilité du placement, ETA aberrante des courses à appel unique) : ticket
  séparé, signal non temporel.
- Pas de seuil d'ETA pour masquer un train (décision produit ferme).
