# Intégration PRIM — valeurs vérifiées le 2026-07-22

Clé stockée dans `.env` (racine), variable `PRIM_API_KEY` — non commitée.

## Authentification — CONFIRMÉ ✅

- En-tête HTTP : **`apikey`** (minuscule). Testé : `stop-monitoring` → HTTP 200.
- Base marketplace : `https://prim.iledefrance-mobilites.fr/marketplace`

## Endpoints testés

| Endpoint | Résultat | Usage |
|---|---|---|
| `GET /marketplace/stop-monitoring?MonitoringRef=STIF:StopPoint:Q:<id>:` | **200** | Prochains passages à un arrêt (SIRI-SM) |
| `GET /marketplace/estimated-timetable?LineRef=STIF:Line::<id>:` | **200** (~129 Ko) | Horaires estimés temps réel filtrés sur une ligne (SIRI-ET) |
| `GET /marketplace/estimated-timetable` (SANS LineRef) | **200** (~63,5 Mo) | **Flux GLOBAL tout le réseau en 1 appel (SIRI-ET) — SOURCE PRINCIPALE** |
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

→ Impact sur le plan : le poller parse du **SIRI-ET JSON**, pas du protobuf GTFS-RT.
Le reste (snapshot → `PositionEngine` interpolation → endpoints) est inchangé. Les
positions GPS « brutes » de véhicules ne semblent pas exposées → le mode
`INTERPOLATED` devient le mode principal.

## Mise à jour 2026-07-23 — passage au flux GLOBAL

Vérifié : **`estimated-timetable` SANS `LineRef` renvoie tout le réseau en un seul
appel** (63,5 Mo, JSON, même structure), ligne 9 incluse (métro couvert). Le poller
(`RealtimePoller`) ingère donc ce flux et **indexe les courses par `LineRef`**
(`RtSnapshot.byLine`, `forLine(...)`), au lieu d'un appel `requete-ligne` par ligne.

Conséquences :
- **Coût quota indépendant du nombre de lignes** (1 appel/poll pour tout le réseau).
  L'ancien `requete-ligne` (1 appel/min **par ligne**) ne passait pas à l'échelle.
- Au MVP mono-ligne, le poller garde le filtre `?LineRef=` (réponse ~129 Ko) ; retirer
  le filtre = ingestion réseau complète (évolution multi-lignes, sans nouvel appel).
- Poll **PT60S borné aux heures de service** (~05h30–01h30, `inServiceHours`) ≈ 1200/j.
  Quota `estimated-timetable` (doc ~1000/j, à relever à 1500 côté PRIM). Bucket
  **distinct** de `requete-ligne` (constaté : 200 alors que `requete-ligne` était 429).
- `gtfs-rt` sous `/marketplace/` = 403 Cloudflare (chemin inexistant) → piste protobuf
  abandonnée, inutile ici.

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

## ⚠️ Correction 2026-07-24 — la structure des EstimatedCall (analyse du flux live)

**L'affirmation ci-dessous (« 1 seul EstimatedCall par course ») est FAUSSE.** Vérifié sur le
flux réel `estimated-timetable` filtré ligne 9 :

- Le nombre d'`EstimatedCall` par course va de **1 à 22** (souvent plusieurs arrêts à venir).
- **Le tableau n'est PAS trié** : ni par heure, ni par ordre d'arrêt (le champ `Order` est absent).
  Sur ~50 % des courses, `EstimatedCall[0]` n'est PAS l'arrêt le plus proche. → **il faut trier
  par heure et choisir l'arrêt le plus tôt à venir** (bug corrigé, cf. `RealtimePoller`/`PositionEngine`).
- **Aucun `RecordedCalls`** : les arrêts déjà desservis ne sont pas fournis. Un train en marche a
  donc typiquement TOUS ses appels dans le futur ; ~1/3 des courses ont un appel tout juste passé.
- `OriginRef` est souvent `null` ; ~17/47 courses sont des **départs futurs** (prochain arrêt à
  +8–27 min) mêlés aux trains en circulation.

**Interpolation retenue** (sans walk-back) : arrêt imminent = plus tôt à venir ; si un arrêt passé
est présent → interpolation aux **vraies heures** entre lui et le prochain (capte le temps à quai) ;
sinon → segment `arrêt-tracé-précédent → prochain`, durée théorique GTFS. Fraction bornée [0,1] :
un train lointain/futur se fige à son arrêt précédent au lieu d'être reculé.

---

## ⚠️ (obsolète) Structure supposée de la réponse SIRI-ET

Pour la ligne 9 : **60 `EstimatedVehicleJourney`**, ~~**1 seul `EstimatedCall` par course** =
le **prochain arrêt uniquement**~~ (voir correction ci-dessus) :

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

## Mise à jour 2026-07-29 — mesures sur le flux global et corrections

Relevé sur un snapshot réel de `estimated-timetable` (09h52) et sur le GTFS IDFM du jour,
à l'occasion du passage du MVP mono-ligne (9) au métro complet (16 lignes).

### Volumétrie

| Mesure | Valeur |
|---|---|
| Flux global brut | 45,6 Mo JSON |
| **Avec `Accept-Encoding: gzip`** | **3,96 Mo** (×11,5), 5,8 s |
| Projection | ~4,7 Go/jour (vs ~55 Go sans gzip) |
| Courses, toutes lignes | 12 018 sur 1 013 lignes |
| **Courses métro** | **705** |
| Courses à un seul `EstimatedCall` | 254 / 705 = **36 %** |
| Courses dont la donnée dépasse 2 min | 110 / 705 = 16 % (médiane 0,4 min, max 16,8) |

**PRIM sert le flux global en gzip** — le `HttpClient` de Java ne le négocie pas seul, il
faut poser l'en-tête `Accept-Encoding: gzip` et décompresser soi-même (`RealtimePoller`,
cf. `com/mapidf/rt/RealtimePoller.java`).

### Corrections

- **`DatedVehicleJourneyRef` est renseigné sur les 705 courses métro.** La doc plus haut
  (« obsolète », section suivante) supposait l'inverse ; l'identité composite de secours
  (`lineRef|directionRef|destination|premierAppel`) construite par `RealtimePoller` ne
  sert donc jamais pour le métro, et l'identité des trains est stable entre deux polls —
  c'est ce qui permet l'animation continue d'un snapshot à l'autre.
- **`OriginRef` est présent comme clé mais vide (`{}`)** sur les 705 courses — la doc avait
  raison de le dire inexploitable, il manquait juste la précision que la clé existe et que
  seule sa valeur est vide. Idem `RouteRef`, `OriginName`, `VehicleJourneyName`.
- **`RecordedAtTime` existe sur chaque course** et n'était pas exploité jusqu'ici (il est
  désormais parsé par `RealtimePoller` mais pas utilisé pour du filtrage). **Ce n'est pas
  un signal de perturbation** : mesuré pendant une perturbation réelle de la ligne 8,
  celle-ci avait la donnée **la plus fraîche** du réseau (2 % de courses au-delà de 2 min,
  contre 73 % sur la 3bis). La perturbation se lit dans `DepartureStatus: DELAYED` — 14 %
  de ses appels, le taux le plus élevé du réseau. Ne pas s'en servir comme proxy de retard.
- Pas de champ `Order` sur les `EstimatedCall` (confirmé, cf. correction 2026-07-24
  ci-dessus). `DestinationDisplay` est en revanche présent sur chaque appel.

### Référentiel GTFS

- `route_type=1` donne **exactement 16 routes**, une par ligne commerciale, aucun
  `route_short_name` en doublon. La dérivation `IDFM:<code>` → `STIF:Line::<code>:` est
  valide sur les 16, toutes présentes dans le flux.
- **14 couleurs distinctes pour 16 lignes** : la 13 et la 3bis partagent `#82C8E6`, la 6 et
  la 7bis `#82DC73` (le T4 aussi, à retenir pour le tram).
- `stop_times.txt` fait 909 Mo décompressé (10,5 M lignes) dont **941 959 pour le métro** ;
  **915** suffisent avec les seuls parcours représentatifs des branches retenues.
- Tracés : 112 candidats sur le métro → **37 retenus** par couverture gloutonne. Sans elle,
  la ligne 7 a 8 arrêts jusqu'à **1547 m** du tracé retenu. Trains écartés : 4,1 % →
  **0,6 %** (véhicules dont aucune branche ne contient l'arrêt imminent, cf.
  `PositionEngine.computeAll`, compteur `mapidf.position.unplaced`, taggé `line`). Le nombre de
  courses retenues par ligne est exposé en jauge `mapidf.rt.journeys` (tag `line`) : c'est le
  garde-fou qui rend visible une ligne tombée à zéro train.
- Stations : 781 quais, tous dotés d'un `parent_station` présent en `location_type=1` →
  **321 stations**, dont **61 correspondances** (jusqu'à 5 lignes).
- Aucun `calendar.txt`/`calendar_dates.txt` chargé : le loader ne répond pas à un horaire
  théorique daté, seulement à l'ordre et l'espacement des arrêts (limitation assumée,
  cf. Javadoc de `GtfsStaticLoader`).
