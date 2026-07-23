# Plan — Migration temps réel GTFS-RT global (TDD, subagent-driven)

*2026-07-23 — met en œuvre `specs/2026-07-23-migration-gtfs-rt-global.md`.*

Principe : le cœur métier (`PositionEngine`, front) ne bouge pas ; on remplace
l'ingestion mono-ligne SIRI par une ingestion **réseau** GTFS-RT. Chaque tâche =
un test qui échoue d'abord, puis le code qui le fait passer. `./mvnw verify` vert
à chaque fin de tâche.

## Task 0 — Vérification du flux (BLOQUANT, pas de code produit)
- **Pré-requis utilisateur** : abonnement à l'API GTFS-RT « Trip Updates – requête
  globale » sur PRIM → URL de requête + confirmation du quota.
- Récupérer le flux **une fois** (script jetable, en-tête `apikey`), `parseFrom`,
  et **confirmer** : la ligne 9 `IDFM:C01379` est présente avec des
  `stop_time_update`. Noter le format exact des `trip_id` / `stop_id` / du champ
  temps (epoch vs ISO) pour caler le parsing.
- **Sortie** : go/no-go. Si no-go → appliquer §7 de la spec (fallback SIRI borné).

## Task 1 — Dépendance protobuf
- Ajouter `gtfs-realtime-bindings` au `pom.xml`. `./mvnw verify` compile.

## Task 2 — Modèle de snapshot réseau
- Test : `RtSnapshot` (réseau) expose `forRoute(routeId)` → liste immuable des
  courses de cette ligne ; `empty()` renvoie une liste vide pour toute ligne.
- Impl : `RtSnapshot(asOf, Map<String, List<LiveJourney>>)` + `forRoute`.

## Task 3 — Parsing GTFS-RT TripUpdates
- Test : fixture protobuf réduite (2-3 `FeedEntity`, dont ligne 9) →
  `RealtimePoller.parse(bytes, now)` indexe correctement par `route_id`, extrait
  prochain arrêt + ETA + statut. Cas limites : entité sans `TripUpdate`, sans
  `stop_time_update`, temps en `arrival` seul.
- Impl : `FeedMessage.parseFrom`, boucle entités, mapping du §4.4.

## Task 4 — Fetch protobuf + résilience
- Test : `fetch()` sur HTTP non-2xx lève → snapshot conservé, compteur d'échec++.
  Corps non-protobuf → idem (parse lève, snapshot conservé).
- Impl : GET binaire, `Content-Type` protobuf, réutilise le durcissement HTTP existant.

## Task 5 — Contrôleur multi-lignes
- Test IT : snapshot réseau injecté → `GET /lines/9/vehicles` ne renvoie que les
  courses de la ligne 9, positionnées par `PositionEngine`.
- Impl : `poller.current().forRoute(lineProperties.gtfsRouteId())`.

## Task 6 — Config & heures de service
- `realtime-base-url` = endpoint GTFS-RT global ; commentaire quota mis à jour.
- Optionnel : garde « heures de service » dans `poll()` (skip 01h30–05h00).
- `application-test.yml` : flux vide (hermétique, déjà en place).

## Task 7 — Nettoyage & doc
- Retirer le code SIRI `requete-ligne` devenu mort (ou le garder derrière un flag
  si on veut le fallback §7). Mettre à jour `backend/docs/prim-integration.md`.
- `./mvnw verify` + build front verts. Revue finale.

## Notes
- Front : **aucun changement** (contrat `/vehicles` identique).
- Un seul flux global sert toutes les lignes → le cache/snapshot backend protège
  déjà le quota quel que soit le trafic front.
