# MapIDF — suivi temps réel du métro parisien

## Démarrage
1. `export PRIM_API_KEY=<votre clé PRIM>`
2. `docker compose up --build`
3. Front : http://localhost:8080 — API : http://localhost:8000/api/network
4. Santé backend : http://localhost:9000/actuator/health

## Développement
- Backend : `cd backend && ./mvnw spring-boot:run` (API :8000, Actuator :9000)
- Front : `cd frontend && npm run dev` (proxy /api → :8000)
- Tests : `cd backend && ./mvnw test`

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
