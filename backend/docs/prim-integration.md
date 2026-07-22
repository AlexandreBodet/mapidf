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

## Identifiants confirmés (référentiel ILICO `getData`)

- **Ligne 9 métro = `C01379`** (`FR1:Line:C01379:`, Name "9", mode metro, RATP). ✅
- **LineRef SIRI** : `STIF:Line::C01379:`
- **ID_Line (icônes)** : `C01379`
- Requête référentiel : `GET /marketplace/ilico/getData?method=getlc&Name=9&TransportMode=metro&format=json`

## Endpoints retenus (depuis les swaggers PRIM)

| Besoin | Endpoint | Auth | Note |
|---|---|---|---|
| Temps réel ligne | `GET /marketplace/requete-ligne?LineRef=STIF:Line::C01379:` | `apikey` | SIRI-ET par course, ~113 Ko, 200 OK |
| (variante globale) | `GET /marketplace/estimated-timetable?LineRef=ALL` | `apikey` | tout le réseau couvert |
| Logo de ligne (bonus front) | `GET /marketplace/ilico/getIcon/C01379` | **`Authorization`** | SVG ; en-tête différent ! |
| Perturbations | `GET /marketplace/general-message?LineRef=...` | `apikey` | param requis |

## ⚠️ Structure réelle de la réponse SIRI-ET — impact fort sur l'interpolation

Pour la ligne 9 : **60 `EstimatedVehicleJourney`**, mais **1 seul `EstimatedCall` par course** =
le **prochain arrêt uniquement** :

```json
"EstimatedVehicleJourney": [{
  "LineRef": {...}, "DirectionRef": {...},
  "DatedVehicleJourneyRef": {...},          // ↔ course, à rapprocher d'un trip GTFS
  "DestinationName": [{"value": "Pont de Sèvres"}],
  "EstimatedCalls": { "EstimatedCall": [{
    "StopPointRef": {"value": "STIF:StopPoint:Q:463221:"},
    "ExpectedDepartureTime": "2026-07-22T14:16:08.173Z",
    "DestinationDisplay": [{"value": "Pont de Sèvres"}],
    "DepartureStatus": "ON_TIME"
  }]}
}]
```

Conséquence : on ne dispose PAS de la séquence horaire complète par course, seulement du
**prochain arrêt + son ETA + la destination/direction**. Le calcul de position devient :

1. SIRI-ET → pour chaque course : `nextStop` (`StopPointRef`), `ETA`, `DirectionRef`, destination.
2. GTFS statique → séquence ordonnée des arrêts par sens + positions le long du tracé +
   durée théorique du segment `arrêt précédent → nextStop`.
3. Interpolation : `arrêt précédent` = celui qui précède `nextStop` dans le sens `DirectionRef` ;
   fraction parcourue = `1 - (ETA - now) / dureeSegmentThéorique`, bornée à [0,1] ;
   position = point sur le tracé à cette fraction du segment.

→ **Task 6** : parser SIRI-ET JSON (extraire par course : nextStop, ETA, direction, destination).
→ **Task 7** : interpolation « ETA jusqu'au prochain arrêt » (et non plus « entre deux arrêts d'un
   horaire complet »). Mapping `StopPointRef` SIRI ↔ `stop_id` GTFS à établir (le n° `463221`
   apparaît dans le GTFS).

## À confirmer / relever ensuite

- [ ] **GTFS statique** : dataset open data `offre-horaires-tc-gtfs-idfm` sur
      `data.iledefrance-mobilites.fr` — téléchargement direct, **sans clé**.
      Relever l'URL du zip et le `route_id` ligne 9 (probable `IDFM:C01379`).
- [ ] Mapping `StopPointRef` (`STIF:StopPoint:Q:<n>:`) ↔ `stop_id` GTFS.
- [ ] Rattachement `DatedVehicleJourneyRef` (SIRI) ↔ `trip_id` (GTFS), si possible.
- [ ] Quotas d'appel PRIM (à surveiller côté poller).
