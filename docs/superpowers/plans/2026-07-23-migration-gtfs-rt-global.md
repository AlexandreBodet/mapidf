# Plan — Migration temps réel vers flux global `estimated-timetable` (TDD)

*2026-07-23 — met en œuvre `specs/2026-07-23-migration-gtfs-rt-global.md`.*

> **Task 0 = GO** (vérifié) : `estimated-timetable` renvoie tout le réseau en JSON,
> ligne 9 incluse, dans la structure déjà parsée. **Pas de protobuf.** Le plan
> initial (GTFS-RT/protobuf) est abandonné au profit de ce plan-ci, bien plus court.

Principe : on ne change que **l'ingestion**. `PositionEngine`, contrat `/vehicles`,
front = inchangés. TDD : test rouge d'abord, `./mvnw verify` vert à chaque tâche.

## Task 1 — Snapshot indexé par ligne
- Test : `RtSnapshot` expose `forLine(lineRef)` → courses de cette ligne (liste
  immuable, vide si absente) ; `empty()` OK.
- Impl : `RtSnapshot(asOf, Map<String,List<LiveJourney>>)` + `forLine`. `LiveJourney`
  gagne `lineRef` (déjà lisible dans le SIRI).

## Task 2 — Parser multi-lignes
- Test : fixture SIRI `estimated-timetable` (2 lignes, dont la 9) → `parse()` indexe
  par `LineRef`, extrait par course : prochain arrêt, ETA, statut, destination, sens.
  Réutilise la fixture réelle capturée (ligne 9, 82 courses) réduite.
- Impl : adapter `parse()` pour boucler toutes les `EstimatedVehicleJourney` et les
  ranger par `LineRef` (au lieu d'une seule liste). Champs : parser **inchangé**.

## Task 3 — Poller sur `estimated-timetable`
- Test : `pollOnce` avec fetcher bouchonné (fixture) → snapshot réseau peuplé ;
  non-2xx / corps invalide → snapshot conservé + compteur d'échec++ (déjà en place).
- Impl : URL `estimated-timetable`. `LineRef` **optionnel** via config (liste de
  lignes suivies) : présent au MVP (léger), absent = global. En-tête `apikey` inchangé.

## Task 4 — Contrôleur
- Test IT : snapshot réseau injecté → `GET /lines/9/vehicles` ne renvoie que la ligne 9.
- Impl : `poller.current().forLine(lineProperties.siriLineRef())`.

## Task 5 — Config & cadence quota
- `realtime-base-url` → `.../marketplace/estimated-timetable`.
- `app.line` : liste de lignes suivies (MVP = ligne 9 → filtre `LineRef`).
- Cadence : **PT90S** (≈960/j < 1000) ou PT60S + garde heures de service
  (skip ~01h30–05h00). Commentaire quota mis à jour.
- `application-test.yml` : flux vide (hermétique) inchangé.

## Task 6 — Nettoyage & doc
- Retirer l'ancien chemin `requete-ligne` (mort).
- MAJ `backend/docs/prim-integration.md` (endpoint global, quotas réels constatés :
  `estimated-timetable` global OK / `stop-monitoring` 1M/j / `general-message` 20k/j).
- `./mvnw verify` + build front verts. Revue finale.

## À confirmer côté PRIM (non bloquant pour coder)
- Quota exact de `estimated-timetable` (doc ~1000/j) → fige la cadence par défaut.
- Si relevable, PT60S 24/7 (1440/j) redevient envisageable.

## Notes
- Front : **aucun changement**.
- Fixture de test : dérivée de la capture réelle `estimated-timetable?LineRef=…C01379`
  (82 courses) — réduire à 2-3 courses sur 2 lignes pour le test unitaire.
