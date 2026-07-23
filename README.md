# MapIDF — suivi temps réel de la ligne 9

## Démarrage
1. `export PRIM_API_KEY=<votre clé PRIM>`
2. `docker compose up --build`
3. Front : http://localhost:8080 — API : http://localhost:8000/api/lines/9/vehicles
4. Santé backend : http://localhost:9000/actuator/health

## Développement
- Backend : `cd backend && ./mvnw spring-boot:run` (API :8000, Actuator :9000)
- Front : `cd frontend && npm run dev` (proxy /api → :8000)
- Tests : `cd backend && ./mvnw test`

## Configuration
Le MVP est mono-ligne : le backend sert uniquement la ligne configurée par
`app.line.gtfs-route-id` (+ `app.line.siri-line-ref` côté SIRI temps réel). Le `LINE_ID`
utilisé côté frontend n'est qu'un libellé d'URL (`/api/lines/{id}/...`) — il n'est pas
utilisé pour la résolution de la ligne côté backend.

Pour faire tourner l'appli avec de vraies données, renseignez :
- `app.prim.gtfs-static-url` (dataset GTFS statique PRIM)
- `app.line.gtfs-route-id` (le vrai `route_id` GTFS, ex. `IDFM:C01379`)
- `PRIM_API_KEY` (clé d'accès PRIM)

`docker compose` charge automatiquement le fichier `.env` à la racine, donc
`export PRIM_API_KEY` avant `docker compose up` est optionnel si `.env` est renseigné.
