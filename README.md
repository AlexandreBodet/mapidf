# MapIDF — suivi temps réel du métro parisien

## Démarrage
1. `cp .env.example .env`, puis renseigner `PRIM_API_KEY` et `POSTGRES_PASSWORD`
2. `docker compose up --build`
3. Front : http://localhost:8080 — API : http://localhost:8000/api/network
4. Santé backend : http://localhost:9000/actuator/health

Le `.env` (gitignoré) est la **seule** source des identifiants : aucun n'a de valeur par défaut
dans le code, pour qu'aucun ne puisse suivre le projet jusqu'en production. Les trois chemins de
lancement le lisent — `docker compose` nativement, IntelliJ via sa configuration de run, et
`./mvnw spring-boot:run` via `spring.config.import` dans
[application.yml](backend/src/main/resources/application.yml). Une configuration incomplète
**empêche le démarrage**, en nommant les variables manquantes : c'est le rôle de
`ConfigurationGuard`, car Spring seul ne lèverait pas — le Binder garde le texte littéral
(`${POSTGRES_PASSWORD}`) et l'application démarrerait à moitié.

## Développement
- Backend : `cd backend && ./mvnw spring-boot:run` (API :8000, Actuator :9000)
- Base seule (backend hors Docker) : `cd backend && docker compose up -d`
- Front : `cd frontend && npm run dev` (proxy /api → :8000)
- Tests : `cd backend && ./mvnw test` (tests unitaires seuls, rapide) — mais la vérification de
  référence du projet est `cd backend && ./mvnw verify` (build complet + tests d'intégration
  Testcontainers ; nécessite Docker).

## Premier démarrage
À la première exécution (base vide, ou après une migration Flyway), le backend télécharge le
GTFS IDFM complet (~125 Mo, mesuré le 2026-07-30) et le charge avant que la carte n'ait quoi
que ce soit à afficher. Pendant ce temps, `GET /api/network` répond **200 avec un réseau vide** (pas 404) : ce n'est pas
une panne. Le front l'affiche désormais explicitement (« Plan en préparation ») et réessaie
toutes les 10 s, au lieu de rester sur une carte vide jusqu'à un rechargement manuel. La
progression se suit dans les logs backend : `[GTFS] N ligne(s) découverte(s) pour
les modes [...]`, puis par ligne `[GTFS] ligne X (nom) : N candidate(s) → M branche(s)
retenue(s)`, puis `[GTFS] N route(s), M branche(s), P stop_time(s) persistés`, et enfin
`[REGISTRY] N ligne(s), M branche(s), P station(s)` une fois le réseau prêt à être servi.

## Configuration
Le périmètre suivi est le métro complet, découvert automatiquement par mode GTFS via
`app.network.modes` (route_id à exclure via `app.network.exclude`) — il n'y a plus de
ligne unique configurée à la main, ni de `LINE_ID` côté frontend : le front charge le
réseau dynamiquement via `GET /api/network`.

Le mode `METRO` est **pré-configuré** dans `application.yml` (`app.network.modes:
[METRO]`, et `gtfs-static-url` = GTFS IDFM complet ~125 Mo, filtré en streaming par le
backend pour ne garder que les lignes du ou des modes suivis). Rien d'autre à configurer que
le `.env` décrit plus haut (`PRIM_API_KEY` + `POSTGRES_PASSWORD`). Pour suivre d'autres
modes (ex. tram), ajustez `app.network.modes` (et le `gtfs-static-url` si besoin).

## API
- `GET /api/network` — lignes, branches et tracés du réseau suivi.
- `GET /api/vehicles` — positions courantes des véhicules (tous modes/lignes suivis).
- `GET /api/stations/{id}/departures` — prochains passages à une station, groupés par ligne.
- `GET /api/disruptions` — perturbations **en cours** des lignes suivies (les travaux à venir
  sont écartés), avec la pire gravité par ligne et les stations dont un quai est touché.

## Utilisation — échelle de zoom
La carte se peuple progressivement avec le zoom : les tracés des lignes sont visibles à tout
niveau, les stations apparaissent à partir du zoom 11, les trains à partir du zoom 12 (zoom
d'ouverture de la carte) et les noms de stations à partir du zoom 13. En dessous de 12, aucun
train n'est affiché même si des courses circulent.

Le sélecteur de lignes (bas de l'écran) fonctionne par isolement : un premier clic sur une ligne
isole cette ligne seule ; les clics suivants ajoutent ou retirent des lignes du sous-ensemble
affiché, sans jamais le vider complètement ; « tout afficher » revient à toutes les lignes. Les
pastilles de ligne affichées dans la fiche d'une station isolent elles aussi la ligne cliquée,
quel que soit le sous-ensemble affiché auparavant.

## Données, sources et licences

Le code de MapIDF est sous [licence MIT](LICENSE). Les **données ne le sont pas** : elles
appartiennent à leurs producteurs et gardent leurs propres conditions.

| Source | Ce qu'on en tire | Licence |
|---|---|---|
| [Réseaux urbains et interurbains d'Île-de-France Mobilités](https://transport.data.gouv.fr/datasets/reseau-urbain-et-interurbain-dile-de-france-mobilites) — GTFS statique + SIRI Lite via PRIM | tracés, arrêts, couleurs de ligne, horaires temps réel | [Licence Mobilité](https://cloud.fabmob.io/s/eYWWJBdM3fQiFNm) (v. 03.02.2021) |
| [OpenFreeMap](https://openfreemap.org) / [OpenMapTiles](https://www.openmaptiles.org) / [OpenStreetMap](https://www.openstreetmap.org/copyright) | fond de carte (rues, POI) | ODbL (données OSM), attribution fournie par la TileJSON |
| Natural Earth (relief basse résolution du style Liberty) | ombrage aux zooms lointains | domaine public |

Ce n'est **pas** de l'ODbL : les données IDFM sont sous *Licence Mobilité*, un texte proche
mais distinct, avec des clauses propres (art. 5.2 compatibilité avec la stratégie de mobilité,
5.7 neutralité et loyauté, 5.6.d re-partage sur le Point d'Accès National).

### Obligations tenues dans le code — ne pas les retirer

- **Mention de la source (art. 5.4)** : posée en `customAttribution` dans
  [MapView.tsx](frontend/src/map/MapView.tsx), avec `compact: false` pour que l'attribution
  reste dépliée au lieu d'être repliée derrière le bouton « ⓘ » (défaut MapLibre).
- **Neutralité et loyauté (art. 5.7)** : le pied du sélecteur de lignes
  ([LinePicker.tsx](frontend/src/ui/LinePicker.tsx)) énonce que la position est **estimée**
  (le métro n'a pas de GPS) et affiche l'**heure du dernier snapshot** — la licence interdit
  d'induire en erreur sur le contenu comme sur sa date de mise à jour.
- **Clé PRIM personnelle (art. 4.1)** : `PRIM_API_KEY` vit dans `.env` (gitignoré), ne sort
  jamais du backend et n'est jamais exposée au frontend.

### À traiter avant tout déploiement public

L'usage strictement interne n'est pas une « utilisation publique » au sens de l'art. 5.6.c :
les obligations ci-dessous ne se déclenchent qu'à l'exposition de l'appli hors de la machine
de dev.

- **Partage à l'identique (art. 5.5 et 5.6.d)** : le PostGIS peuplé par `GtfsStaticLoader` est
  une base de données dérivée. Servir directement ses tracés et arrêts via `/api/network`
  ouvert au public peut faire tomber l'art. 5.6.d (publication de la base dérivée sur
  transport.data.gouv.fr, sous le jeu initial et au format d'origine). L'art. 5.6.b (utiliser
  la base pour produire une « Création » n'engendre pas de base dérivée) est l'argument
  inverse, mais il s'affaiblit à mesure que l'API expose la donnée brute.
- **CGU de PRIM et « Chartes et prescriptions » IDFM** : non vérifiées ici (le site PRIM
  renvoie 403 derrière Cloudflare hors navigateur). Deux points y sont traités et échappent
  à la vérification faite dans ce repo — les quotas et l'éventuelle compensation financière
  (l'art. 4.3 de la licence renvoie aux CGU de la plateforme), et l'usage des **marques** :
  la Licence Mobilité exclut de son périmètre « toute marque déposée associée à la Base de
  données », ce qui couvre indices de ligne, couleurs officielles et logos. À lire depuis un
  navigateur avant publication.
