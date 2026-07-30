# MapIDF — suivi temps réel du métro parisien

## Démarrage
1. `export PRIM_API_KEY=<votre clé PRIM>`
2. `docker compose up --build`
3. Front : http://localhost:8080 — API : http://localhost:8000/api/network
4. Santé backend : http://localhost:9000/actuator/health

## Développement
- Backend : `cd backend && ./mvnw spring-boot:run` (API :8000, Actuator :9000)
- Front : `cd frontend && npm run dev` (proxy /api → :8000)
- Tests : `cd backend && ./mvnw test` (tests unitaires seuls, rapide) — mais la vérification de
  référence du projet est `cd backend && ./mvnw verify` (build complet + tests d'intégration
  Testcontainers ; nécessite Docker).

## Premier démarrage
À la première exécution (base vide, ou après une migration Flyway), le backend télécharge le
GTFS IDFM complet (~109 Mo) et le charge avant que la carte n'ait quoi que ce soit à afficher.
Pendant ce temps, `GET /api/network` répond **200 avec un réseau vide** (pas 404) : ce n'est pas
une panne. La progression se suit dans les logs backend : `[GTFS] N ligne(s) découverte(s) pour
les modes [...]`, puis par ligne `[GTFS] ligne X (nom) : N candidate(s) → M branche(s)
retenue(s)`, puis `[GTFS] N route(s), M branche(s), P stop_time(s) persistés`, et enfin
`[REGISTRY] N ligne(s), M branche(s), P station(s)` une fois le réseau prêt à être servi.

## Configuration
Le périmètre suivi est le métro complet, découvert automatiquement par mode GTFS via
`app.network.modes` (route_id à exclure via `app.network.exclude`) — il n'y a plus de
ligne unique configurée à la main, ni de `LINE_ID` côté frontend : le front charge le
réseau dynamiquement via `GET /api/network`.

Le mode `METRO` est **pré-configuré** dans `application.yml` (`app.network.modes:
[METRO]`, et `gtfs-static-url` = GTFS IDFM complet ~109 Mo, filtré en streaming par le
backend pour ne garder que les lignes du ou des modes suivis). **Seule la clé PRIM est
requise** : renseignez `PRIM_API_KEY` dans le fichier `.env` à la racine (chargé
automatiquement par `docker compose`), puis `docker compose up`. Pour suivre d'autres
modes (ex. tram), ajustez `app.network.modes` (et le `gtfs-static-url` si besoin).

## API
- `GET /api/network` — lignes, branches et tracés du réseau suivi.
- `GET /api/vehicles` — positions courantes des véhicules (tous modes/lignes suivis).
- `GET /api/stations/{id}/departures` — prochains passages à une station, groupés par ligne.

## Utilisation — échelle de zoom
La carte se peuple progressivement avec le zoom : les tracés des lignes sont visibles à tout
niveau, les stations apparaissent à partir du zoom 11, puis à partir du zoom 12 (zoom
d'ouverture de la carte) les trains et les noms de stations apparaissent ensemble. En dessous de
12, aucun train n'est affiché même si des courses circulent.

Le sélecteur de lignes (bas de l'écran) fonctionne par isolement : un premier clic sur une ligne
isole cette ligne seule ; les clics suivants ajoutent ou retirent des lignes du sous-ensemble
affiché, sans jamais le vider complètement ; « tout afficher » revient à toutes les lignes. Les
pastilles de ligne affichées dans la fiche d'une station isolent elles aussi la ligne cliquée,
quel que soit le sous-ensemble affiché auparavant.
