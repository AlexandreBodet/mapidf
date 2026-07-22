# MapIDF — Suivi temps réel des transports franciliens sur carte

*Document de conception — 2026-07-22*

## 1. Objectif

Application web affichant les véhicules de transport en commun d'Île-de-France
qui **se déplacent en quasi temps réel sur une carte interactive**.

- **Effet visé** : voir les rames/véhicules glisser le long des lignes.
- **MVP** : une seule ligne — **métro ligne 9** (Pont de Sèvres ↔ Mairie de
  Montreuil) comme référence, mais l'identifiant de ligne reste paramétrable.
- **Finalité** : vrai produit destiné à être déployé. On pense donc cache,
  résilience, monitoring et coûts dès l'architecture — sans sur-ingénierie
  prématurée sur le périmètre MVP.

## 2. Contexte données (IDFM / PRIM)

Source : plateforme open data **PRIM** d'Île-de-France Mobilités
(`prim.iledefrance-mobilites.fr`). Nécessite une **clé API**.

Formats utilisés (endpoints vérifiés le 2026-07-22, cf.
`backend/docs/prim-integration.md`) :
- **GTFS statique** (open data, sans clé) : lignes, arrêts, tracés (`shapes`),
  trips, `stop_times` (offre théorique). Rafraîchi rarement (ex. 1×/jour).
- **SIRI Lite — `estimated-timetable` / `requete-ligne`** (JSON, en-tête `apikey`) :
  temps réel par ligne. Endpoint retenu :
  `GET /marketplace/requete-ligne?LineRef=STIF:Line::C01379:` (ligne 9 = `C01379`).

**Contrainte clé — la donnée temps réel d'IDFM est en SIRI, pas en GTFS-RT, et
elle est parcimonieuse.** Pour la ligne 9, la réponse liste ~60 courses
(`EstimatedVehicleJourney`), mais **chaque course ne fournit que son PROCHAIN arrêt**
(`EstimatedCall` unique : `StopPointRef`, `ExpectedDepartureTime`, destination,
`DepartureStatus`). Il n'y a **pas** de position GPS de véhicule ni de séquence
horaire complète. Le produit calcule donc une **position estimée unique** par
course (`source = INTERPOLATED`), à partir de : prochain arrêt + ETA temps réel +
géométrie & horaires théoriques GTFS. Un mode `REALTIME` (GPS snappé) reste prévu
dans le modèle pour une source future qui exposerait des positions, mais n'est pas
alimenté au MVP.

## 3. Architecture générale

```
┌─────────────┐   poll ~4s    ┌──────────────────┐   poll 5-20s   ┌──────────┐
│   React      │ ────────────> │  Spring Boot      │ ─────────────> │   IDFM    │
│  + MapLibre  │ <──────────── │  (proxy + moteur) │ <───────────── │   PRIM    │
│  (tween RAF) │  positions    │  + PostgreSQL/    │  SIRI-ET/GTFS  │           │
└─────────────┘   JSON        │   PostGIS         │                └──────────┘
                               └──────────────────┘
```

Décisions structurantes validées :
- **Transport des données** : polling REST (front → backend ~4s) +
  **interpolation/tween côté client** le long de la géométrie connue.
  SSE/WebSocket = évolution future (seul le canal change, pas le calcul).
- **Carte** : **MapLibre GL JS** (WebGL, vectoriel, animation fluide, open-source).
- **Stockage GTFS statique** : **PostgreSQL + PostGIS** dès le départ.
- Le front ne parle **qu'au** backend (clé PRIM jamais exposée, CORS maîtrisé).

Le backend a **deux boucles indépendantes** :
1. Rafraîchissement du GTFS statique (rare).
2. Poll du temps réel (fréquent).

## 4. Backend Spring Boot

Unités à responsabilité unique :

### `GtfsStaticService`
Au démarrage puis refresh quotidien : télécharge/parse le GTFS de la ligne et le
charge en base PostGIS.
- `shapes` → colonne géométrie `LINESTRING` (SRID 4326).
- `stops` → colonne géométrie `POINT`.
- `trips`, `stop_times`, `routes` → tables relationnelles.
Le tracé de la ligne active est aussi mis en cache mémoire pour le calcul chaud.

### `RealtimePoller`
`@Scheduled` : poll le flux **SIRI-ET JSON** (`requete-ligne`), parsing via Jackson.
Conserve le dernier snapshot temps réel en mémoire, thread-safe (`AtomicReference`
sur une structure immuable). Extrait par course : `journeyRef`, `directionRef`,
`destination`, **prochain arrêt** (`StopPointRef`) et **ETA** (`ExpectedDepartureTime`).

### `PositionEngine`
Cœur du système. Fonction **pure et déterministe** :
`(tracé ligne, horaires par sens, snapshot temps réel, instant t) → véhicules positionnés`.
Pour chaque course live (prochain arrêt + ETA + destination) :
- Choisir le **sens** (via la destination/terminus), y localiser le prochain arrêt
  et son **arrêt précédent** dans la séquence GTFS.
- **Interpolation par ETA** : `fraction = 1 − (ETA − t) / durée_théorique_segment`,
  bornée à `[0,1]` ; position = point du tracé (JTS `LengthIndexedLine`) à cette
  fraction entre l'arrêt précédent et le prochain ; cap = direction précédent→prochain.
- `source = INTERPOLATED` ; `delaySec` estimé = `ETA − horaire_théorique` de l'arrêt.
Déterminisme volontaire (instant `t` injecté) pour testabilité unitaire, sans DB.

### `VehicleController`
- `GET /api/lines/{id}/shape` — tracé (polyligne) + arrêts. Appelé une fois au
  chargement du front. Cacheable agressivement.
- `GET /api/lines/{id}/vehicles` — snapshot des véhicules calculés à l'instant
  courant.

Clé PRIM et paramètres via configuration (variables d'environnement).

## 5. Contrat d'API (front ↔ backend)

```jsonc
// GET /api/lines/9/shape        (une fois au chargement)
{ "lineId":"9", "color":"#D5C900",         // couleur issue du route_color GTFS
  "shape":[[2.23,48.83], ...],            // polyligne [lng,lat]
  "stops":[{ "id":"...", "name":"...", "lat":48.87, "lng":2.30 }] }

// GET /api/lines/9/vehicles     (toutes les ~4s)
{ "asOf":"2026-07-22T10:15:03Z",
  "vehicles":[
    { "tripId":"...", "lat":48.87, "lng":2.34, "bearing":92,
      "delaySec":45, "headsign":"Mairie de Montreuil",
      "nextStop":"République", "source":"INTERPOLATED" } ] }
```

`source` (`REALTIME` / `INTERPOLATED`) → stylisation différenciée côté front. Au
MVP toutes les positions sont `INTERPOLATED` (calculées depuis l'ETA SIRI) ; le
champ reste pour distinguer une future source GPS. `tripId` = `journeyRef` SIRI.

## 6. Frontend React + MapLibre

- `useLineShape(id)` — charge le tracé une fois, le dessine (couche ligne + arrêts).
- `useVehicles(id)` — poll `/vehicles` toutes les ~4s.
- `VehicleLayer` — pour chaque véhicule, une boucle `requestAnimationFrame` fait
  **glisser** le marqueur de sa position courante vers la nouvelle cible le long
  du tracé (interpolation locale, pas de téléportation). Orientation selon
  `bearing`.
- **Panneau latéral** — clic sur un véhicule → détails (destination, retard,
  prochain arrêt, source de position).

Build front via Vite ; sortie statique.

## 7. Robustesse & déploiement

- **Cache** : le backend sert un snapshot déjà calculé → un pic de trafic front ne
  multiplie pas les appels IDFM (1 poll IDFM sert N clients).
- **Résilience** : si IDFM est indisponible, on continue à servir le dernier
  snapshot connu et on bascule tout en mode `INTERPOLATED` (dégradation gracieuse).
- **Rate-limit** : quotas PRIM respectés côté poller uniquement (jamais amplifiés
  par le nombre de clients).
- **Déploiement** : backend (JAR/Docker) + PostgreSQL/PostGIS + front statique
  derrière un reverse proxy.
- **Monitoring** : Spring Actuator (health, métriques : âge du dernier snapshot,
  succès/échec des polls, nb de véhicules).

## 8. Stratégie de test

- `PositionEngine` pur/déterministe → tests unitaires couvrant : interpolation par
  ETA (fraction bornée), choix du sens via destination, arrêt introuvable, ETA
  dépassée, course sans arrêt précédent (départ terminus).
- `RealtimePoller` : parsing d'une **fixture SIRI-ET JSON** (extrait réel de la ligne 9).
- Tests d'intégration : endpoints REST + accès PostGIS (Testcontainers).

## 9. Hors périmètre (YAGNI pour le MVP)

- Multi-lignes / tout le réseau (métro+RER+tram+Transilien+bus) — élargissement
  ultérieur, l'archi PostGIS y est prête.
- Push temps réel (SSE/WebSocket) — évolution future.
- Comptes utilisateurs, favoris, notifications.
- Calcul d'itinéraire.

## 10. Évolutions prévues (après MVP)

1. Ajout progressif des modes (métro complet → RER/tram → Transilien → bus).
2. Bascule polling → SSE si besoin de latence plus faible.
3. Historisation des positions (analyse de régularité, PostGIS + time-series).
