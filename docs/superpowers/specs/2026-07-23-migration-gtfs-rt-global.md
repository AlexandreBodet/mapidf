# MapIDF — Migration du temps réel vers un flux global

*Document de conception — 2026-07-23 — complète/supersede la section « temps réel »
de `2026-07-22-mapidf-suivi-transport-design.md`.*

> **MISE À JOUR 2026-07-23 (après vérification empirique — Task 0 GO).**
> Le titre parlait de « GTFS-RT » mais la vérification sur données réelles a montré
> une solution **plus simple et sans protobuf** : le endpoint SIRI **`estimated-timetable`**
> (requête *globale*) renvoie **toutes les lignes en un seul appel, en JSON**, dans la
> structure **exactement** consommée par notre `RealtimePoller.parse()` actuel.
> Vérifié avec notre clé : `estimated-timetable?LineRef=STIF:Line::C01379:` → 200,
> 129 Ko, **82 courses ligne 9** ; sans `LineRef` → 63,5 Mo, tout le réseau, ligne 9
> incluse (métro couvert). **On abandonne donc la piste GTFS-RT/protobuf** (chemin
> Cloudflare-bloqué + dépendance inutile) au profit de `estimated-timetable`.
> Les sections 4.1 (protobuf) et le mapping 4.4 ci-dessous sont **caducs** ; voir la
> section « Approche retenue » et le plan associé.

## 0. Approche retenue (fait foi)

- **Source** : `GET /marketplace/estimated-timetable` (en-tête `apikey`), SIRI-ET JSON.
  - **Sans `LineRef`** → tout le réseau en 1 appel (≈63 Mo).
  - **Avec `LineRef`** → une ligne (≈129 Ko), même structure.
- **Parser** : `RealtimePoller.parse()` **inchangé** (mêmes chemins/ champs SIRI).
- **Snapshot réseau** : indexer les courses **par `LineRef`** (`Map<String, List<LiveJourney>>`)
  pour être prêt multi-lignes sans nouvel appel. Le MVP peut rester **filtré ligne 9**
  (léger) via une liste de lignes configurée ; passer en global (sans filtre) quand on
  élargit le réseau.
- **`PositionEngine`, contrat `/vehicles`, front** : **inchangés**.
- **Quota** : le coût devient **1 appel/poll quel que soit le nombre de lignes**. Reste
  à caler la cadence sur le quota réel de `estimated-timetable` (doc : ~1000/j ;
  à confirmer/relever dans la console PRIM). PT90S (≈960/j sur 24 h) ou PT60S borné
  aux heures de service tiennent sous 1000/j.

## 1. Problème

Le MVP poll le temps réel **par ligne** via SIRI-ET
(`GET /marketplace/requete-ligne?LineRef=STIF:Line::C01379:`).

Deux limites, constatées en usage réel :

1. **Quota.** Le quota PRIM de cette API est **par ligne** (~1000/j, relevé à 1500/j).
   À 1 appel/minute → **1 440 appels/jour**, soit ~tout le quota d'une seule ligne,
   sans marge (avant même le polling nocturne et les redémarrages).
2. **Passage à l'échelle.** Le modèle fait **N appels/minute pour N lignes**. Pour
   le réseau complet (métro + RER + tram + Transilien + bus), c'est des milliers
   d'appels/minute — infaisable. **Impasse structurelle.**

## 2. Décision

Remplacer le poll SIRI par ligne par **un unique flux GTFS-RT global** couvrant
tout le réseau :

- **1 appel/minute pour TOUT le réseau** → **1 440 appels/jour au total**, quel que
  soit le nombre de lignes. Le coût quota devient **indépendant du nombre de lignes**.
- Quota de l'API GTFS-RT nettement plus large que le SIRI par ligne
  (à confirmer sur PRIM, ordre de grandeur attendu ~20 000/j).

### Choix de l'entité GTFS-RT

| Entité GTFS-RT | Contenu | Couvre le métro ? | Usage MapIDF |
|---|---|---|---|
| **TripUpdates** | horaires de passage **prédits** par arrêt (arrival/departure, delay) | **Oui** (prédiction, pas GPS) | **Source principale** → alimente le `PositionEngine` (interpolation) |
| VehiclePositions | position **GPS** exacte des véhicules | Non pour le métro (pas de GPS) ; oui bus/tram | Overlay futur, positions `REALTIME` exactes pour les lignes GPS |
| ServiceAlerts | messages de perturbation | — | Hors périmètre migration |

Le métro fonctionne par signalisation, pas par GPS : **VehiclePositions ne renverra
rien pour la ligne 9**. **TripUpdates** est donc la source qui couvre le métro, et
elle fournit exactement ce dont le `PositionEngine` a besoin (prochain arrêt + ETA).

## 3. Garde-fou préalable (Task 0 — bloquant)

**Avant toute réécriture**, récupérer le flux GTFS-RT TripUpdates global **une fois**
(clé PRIM, en-tête `apikey`) et **vérifier que la ligne 9 (`IDFM:C01379`) y figure**
avec des `stop_time_updates`. Si le métro n'est pas couvert par ce flux, on révise
la stratégie (fallback SIRI restreint aux heures de service, ou autre) **avant** de
coder. On ne migre pas sur une hypothèse non vérifiée.

## 4. Impact technique

Le cœur métier (`PositionEngine`, interpolation, tween front, contrat d'API
`/vehicles`) **ne change pas**. Seule **l'ingestion** du temps réel change.

### 4.1 Dépendance
Ajout du parsing **protobuf** GTFS-RT : `org.mobilitydata:gtfs-realtime-bindings`
(ou `com.google.transit:gtfs-realtime-bindings`). Le flux n'est plus du JSON SIRI
mais un `FeedMessage` protobuf.

### 4.2 `RealtimePoller` → ingestion globale
- `fetch()` : GET du flux GTFS-RT global (URL configurable `app.prim.realtime-base-url`,
  en-tête `apikey` inchangé), corps = `application/x-protobuf`.
- `parse()` : `FeedMessage.parseFrom(bytes)` → pour chaque `FeedEntity` ayant un
  `TripUpdate` : extraire `trip.route_id`, `trip.trip_id`, et le **prochain**
  `stop_time_update` pertinent (`stop_id`, `arrival`/`departure` time, `delay`).
- **Snapshot réseau** : au lieu d'un snapshot mono-ligne, on indexe les courses
  **par `route_id`** (`Map<String, List<LiveJourney>>`), toujours immuable +
  `AtomicReference`. Le MVP n'affiche que la ligne 9, mais le snapshot porte déjà
  tout le réseau (prêt multi-lignes, sans nouvel appel).

### 4.3 `LineController` / `PositionEngine`
- `/vehicles` : `poller.current().forRoute(gtfsRouteId)` → liste des courses de la
  ligne demandée, puis `PositionEngine.computeAll(...)` **inchangé**.
- Le mapping `stopKey` (digits-only) reste valide : GTFS-RT `stop_id` = `IDFM:xxxxx`,
  même normalisation que le GTFS statique.

### 4.4 Mapping des champs
| GTFS-RT TripUpdate | Modèle `LiveJourney` actuel |
|---|---|
| `trip.trip_id` (ou `vehicle.id`) | `journeyRef` |
| `trip.route_id` | clé d'index réseau |
| `stop_time_update.stop_id` | `nextStopRef` |
| `stop_time_update.departure.time` (fallback arrival) | `expectedTime` |
| `stop_time_update.schedule_relationship` / `delay` | `departureStatus` / retard |
| (sens) déduit du terminus / `trip` | `directionRef` |

### 4.5 Quota / heures de service
1 appel/min = 1 440/j. Optionnel : **ne poller que pendant les heures de service**
(~05h00–01h30) → ~1 260/j, marge confortable, zéro appel gâché la nuit. Un seul
flux global, donc ce garde reste trivial même en multi-lignes.

## 5. Stratégie de test (TDD)
- `RealtimePoller.parse()` : **fixture protobuf** GTFS-RT (extrait réel réduit)
  → assertions sur le snapshot réseau (présence ligne 9, prochain arrêt, ETA).
- Snapshot réseau : `forRoute()` filtre correctement par `route_id`.
- Résilience : flux indisponible / corps non-protobuf → snapshot conservé, compteur
  d'échec incrémenté (déjà couvert, à adapter au nouveau format).
- `PositionEngine` : **inchangé** (mêmes tests unitaires).
- IT REST : `/vehicles` sert la ligne 9 depuis un snapshot réseau injecté.

## 6. Hors périmètre de cette migration
- Overlay `VehiclePositions` (GPS bus/tram) — évolution suivante, le champ `source`
  `REALTIME` existe déjà.
- Affichage multi-lignes côté front — le backend devient multi-lignes, le front
  reste sur la ligne 9 au MVP.
- ServiceAlerts / perturbations.

## 7. Migration inverse / risque
Si Task 0 échoue (métro absent du flux global) : conserver SIRI par ligne **borné
aux heures de service** pour la ligne 9 (tient sous 1500/j), et n'utiliser GTFS-RT
global que pour les modes qu'il couvre. Décision reportée à après vérification.
