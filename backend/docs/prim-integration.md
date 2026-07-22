# Intégration PRIM — valeurs vérifiées le 2026-07-22

Clé stockée dans `.env` (racine), variable `PRIM_API_KEY` — non commitée.

## Authentification — CONFIRMÉ ✅

- En-tête HTTP : **`apikey`** (minuscule). Testé : `stop-monitoring` → HTTP 200.
- Base marketplace : `https://prim.iledefrance-mobilites.fr/marketplace`

## Endpoints testés

| Endpoint | Résultat | Usage |
|---|---|---|
| `GET /marketplace/stop-monitoring?MonitoringRef=STIF:StopPoint:Q:<id>:` | **200** | Prochains passages à un arrêt (SIRI-SM) |
| `GET /marketplace/estimated-timetable?LineRef=STIF:Line::<id>:` | **200** (~110 Ko) | **Horaires estimés temps réel par ligne (SIRI-ET) — SOURCE PRINCIPALE** |
| `GET /marketplace/general-message` | 400 sans param, auth OK | Perturbations (SIRI-GM) — nécessite un paramètre |
| `GET /marketplace/gtfs-rt*` | 403 Cloudflare | **N'existe pas sous ce chemin** — IDFM ne publie pas de GTFS-RT « clé en main » ici |

## Découverte structurante

**IDFM expose le temps réel en SIRI Lite (JSON), pas en GTFS-RT protobuf.**
La source adaptée au suivi des véhicules est **`estimated-timetable`** (SIRI-ET) :
pour chaque course (`EstimatedVehicleJourney`) de la ligne, elle fournit les
**heures estimées de passage à chaque arrêt** (`EstimatedCall` avec
`ExpectedArrivalTime` / `ExpectedDepartureTime`). C'est exactement ce qu'il faut
pour **interpoler la position** le long du tracé : pas besoin d'un flux
`TripUpdates` séparé, l'heure estimée intègre déjà le retard.

→ Impact sur le plan : la **Task 6** (poller) parse du **SIRI-ET JSON**, pas du
protobuf GTFS-RT. Le reste (snapshot → `PositionEngine` interpolation → endpoints)
est inchangé. Les positions GPS « brutes » de véhicules ne semblent pas exposées →
le mode `INTERPOLATED` devient le mode principal.

## À confirmer / relever ensuite

- [ ] **LineRef exact de la ligne 9** : `STIF:Line::C01379:` renvoie des données
      (valide) — vérifier que c'est bien la ligne 9 en croisant avec `routes.txt`
      du GTFS (`route_id` / `route_short_name = 9`).
- [ ] **MonitoringRef** des arrêts de la ligne 9 (pour stop-monitoring si besoin).
- [ ] **GTFS statique** : dataset open data `offre-horaires-tc-gtfs-idfm` sur
      `data.iledefrance-mobilites.fr` — téléchargement direct, **sans clé**.
      Relever l'URL du zip et le `route_id` de la ligne 9.
- [ ] Quotas d'appel PRIM (à surveiller côté poller).

## Exemple de réponse SIRI-ET (extrait)

```json
{"Siri":{"ServiceDelivery":{"ResponseTimestamp":"2026-07-22T14:08:37.572Z",
  "ProducerRef":"IVTR_HET", ...
  "EstimatedTimetableDelivery":[{ "EstimatedJourneyVersionFrame":[{ ... }] }] }}}
```
