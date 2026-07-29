# Extension multi-ligne — tout le métro (16 lignes)

Design validé le 2026-07-29. Étend MapIDF du MVP mono-ligne (métro 9) au réseau métro
complet, en construisant un modèle N-lignes générique qui prépare le tram puis le RER.

Toutes les valeurs chiffrées de ce document ont été **mesurées** le 2026-07-29 sur le GTFS
IDFM et un snapshot réel du flux `estimated-timetable` (09h52). Elles ne sont pas estimées.

## Objectif et périmètre

Suivre en temps réel les **16 lignes de métro** (1 à 14, 3bis, 7bis) sur une vue réseau
unique : tous les tracés et tous les trains visibles à l'ouverture, avec un filtre par ligne.

Hors périmètre : tram, RER, Transilien, bus. Le design est fait pour que le tram soit un
changement de configuration, et pour que le RER n'exige pas de refonte du modèle.

## Décisions de cadrage

| Sujet | Décision |
|---|---|
| Périmètre | Les 16 lignes de métro, découvertes automatiquement |
| Vue par défaut | Réseau complet (tracés + trains), filtre par ligne côté client |
| Référentiel | Découvert depuis le GTFS par mode, aucune saisie manuelle |
| Branches | **Traitées** par couverture gloutonne des tracés (décision révisée après mesure) |
| Placement peu fiable | Affichage atténué, jamais de masquage — décision produit inchangée |
| Perturbations | `DepartureStatus: DELAYED` enfin affiché (badge) |

## Mesures de référence (2026-07-29)

### Flux temps réel `estimated-timetable`

| Mesure | Valeur |
|---|---|
| Taille brute du flux global | 45,6 Mo JSON |
| **Transféré avec `Accept-Encoding: gzip`** | **3,96 Mo** (×11,5), 5,8 s |
| Consommation projetée | ~4,7 Go/jour (1 poll/min sur la fenêtre de service) |
| Courses au total (tout mode) | 12 018, sur 1 013 lignes distinctes |
| **Courses métro** | **705** |
| Courses à un seul `EstimatedCall` | 254 / 705 = **36 %** |
| Courses dont la donnée a plus de 2 min | 110 / 705 = 16 % (médiane 0,4 min, max 16,8 min) |

### Corrections à `backend/docs/prim-integration.md`

À reporter dans cette doc pendant l'implémentation :

- **`DatedVehicleJourneyRef` est renseigné sur les 705 courses métro.** Le code suppose
  l'inverse et calcule une identité composite de secours ; pour le métro elle ne sert
  jamais. L'identité des trains est donc stable entre deux polls.
- **`OriginRef` est présent comme clé mais vide (`{}`) sur les 705 courses** — la doc
  actuelle a raison, le champ est inexploitable. Idem `RouteRef`, `OriginName`,
  `VehicleJourneyName`.
- **`RecordedAtTime` existe sur chaque course** et n'est pas exploité aujourd'hui. C'est
  l'horodatage de dernière mise à jour de la course.
- Pas de champ `Order` sur les `EstimatedCall` (confirmé). `DestinationDisplay` est en
  revanche présent sur chaque appel.
- `DepartureStatus` ne prend que les valeurs `ON_TIME` et `DELAYED` dans ce snapshot.

### Référentiel GTFS

| Mesure | Valeur |
|---|---|
| `route_type=1` | **exactement 16 routes**, une par ligne commerciale |
| `route_short_name` en doublon | aucun (les identifiants publics seront `3b` et `7b`) |
| `route_color` distinctes | **14 pour 16 lignes** — voir limitations |
| Quais métro (parcours représentatifs) | 781, **tous** dotés d'un `parent_station` |
| Stations après regroupement | **321** avec les branches (312 sans), toutes présentes dans `stops.txt` en `location_type=1` |
| Correspondances | **61 stations sur 321** : 45 à 2 lignes, 11 à 3, 3 à 4, 2 à 5 (République, Châtelet) |
| Points de tracé sur les 37 branches | **8 110** au total (médiane 229, max 335 par tracé) |
| Dérivation `route_id` → LineRef SIRI | **valide sur les 16 lignes**, toutes présentes dans le flux |
| `trips.txt` | 489 006 courses au total, dont **37 163 pour le métro** |
| `stop_times.txt` | 909 Mo décompressé, 10,5 M lignes, dont **941 959 pour le métro** |
| `stop_times` réellement nécessaires | **915** (parcours représentatifs des branches retenues) |
| Tracés candidats sur le métro | 112 → **37 retenus** par couverture gloutonne |

### Impact des branches (mesuré)

Avec un tracé unique par ligne (« le plus long »), tel qu'aujourd'hui :

- **Ligne 7 : cassée.** 8 arrêts jusqu'à **1547 m** du tracé retenu — le tracé le plus long
  dessert Villejuif, la branche Ivry se projette n'importe où.
- Ligne 10 : 2 arrêts à ~300 m (boucle ouest). Ligne 7bis : 1 arrêt à 385 m (boucle).
- Ligne 13 : géométriquement propre, mais 13 arrêts absents des parcours représentatifs.
- Trains écartés faute d'arrêt connu : **29 / 705 = 4,1 %**, concentrés sur la 13 (17 %),
  la 7 (17 %) et la 4 (8 %).

Avec la couverture gloutonne : **0,6 %** de trains écartés. Les 4 cas résiduels sont sur la
ligne 4 et ne sont pas des branches — ce sont des `StopPointRef` SIRI absents du GTFS métro
(probablement le prolongement de Bagneux).

### Perturbation observée sur la ligne 8

Le snapshot a été capturé pendant une perturbation de la ligne 8 (confirmée par
l'exploitant). Enseignements :

- Elle se manifeste par **14 % d'appels en `DELAYED`**, le taux le plus haut du réseau
  (les autres lignes sont entre 0 et 7 %).
- La ligne 8 a en revanche la donnée la **plus fraîche** du réseau (2 % de courses
  au-delà de 2 min). **`RecordedAtTime` ne détecte donc pas une perturbation** — cohérent
  avec la décision produit de ne jamais masquer un train perturbé, mais cela disqualifie
  ce signal comme critère d'atténuation : il flague surtout la 3bis (73 % de données
  périmées), dont le rafraîchissement est mauvais.

Conséquence de design : la fiabilité s'appuie sur le **nombre d'appels**, `RecordedAtTime`
devient une simple mention informative, et `DELAYED` obtient enfin un affichage.

## Architecture backend

### `LineRegistry` — source unique de vérité

Un bean qui publie, via un `AtomicReference`, la carte des lignes suivies (échange
atomique : aucune requête ne voit un état à moitié rebâti). Il remplace `LineProperties` et
le champ `volatile routeGeometry` de `GtfsStaticService`.

```
TrackedLine  : id public, gtfsRouteId, siriLineRef, shortName, color, mode, List<Branch>
Branch       : gtfsShapeId, direction, terminusName, geom, LengthIndexedLine,
               List<StopOnLine>, Map<stopKey, index>
```

L'`id` public est le `route_short_name` normalisé (`9`, `3b`, `7b`), ce qui garde des URL
lisibles. Aucun doublon possible sur le métro, vérifié.

Le registry est **réhydratable depuis PostGIS** : un redémarrage ne doit pas imposer de
retélécharger 109 Mo de GTFS. Il se remplit depuis la base au démarrage, et est rebâti
après chaque refresh quotidien.

La `Map<stopKey, index>` par branche remplace la recherche linéaire d'`indexOfStop`. Sans
elle, le choix de branche coûterait ~100 000 comparaisons de chaînes par requête
(705 courses × jusqu'à 4 branches candidates × ~35 arrêts).

`ScheduleProvider` **disparaît** : la projection des arrêts sur le tracé se fait à la
construction du registry, une fois par refresh, au lieu d'être cachée séparément.

### Configuration

```yaml
app:
  network:
    modes: [metro]     # route_type GTFS suivis
    exclude: []        # route_id à ignorer
```

Pas de bloc de surcharges : la dérivation du LineRef est vérifiée sur les 16 lignes, et le
garde-fou est observable plutôt que déclaratif — un log au refresh et une gauge
`mapidf.rt.journeys` taggée par ligne rendent immédiatement visible une ligne à zéro train.

### Chargement GTFS

Le loader actuel **accumule toutes les lignes de `stop_times.txt` en mémoire** avant de
persister. Sur la ligne 9 ça passe ; sur 16 lignes ce sont 941 959 entités `StopTime` dans
une seule transaction — l'OOM que le streaming du zip devait éviter revient par la porte de
derrière.

Or seuls les **parcours représentatifs** sont exploités : `ScheduleProvider` n'utilise
aujourd'hui que la course la plus longue par sens. Et comme `calendar.txt` n'est pas chargé,
la table est de toute façon incapable de répondre à une question d'horaire théorique daté.

Nouveau déroulé :

1. `routes.txt` → routes du mode demandé, hors `exclude`. Dérive `siriLineRef`
   (`STIF:Line::<code>:` où `<code>` est la partie après `IDFM:`), lit nom et couleur.
2. `trips.txt` → courses de ces routes, indexées par (route, `direction_id`, `shape_id`).
3. **Passe 1 sur `stop_times.txt`** : compte les arrêts par course retenue
   (37 163 compteurs, mémoire triviale).
4. **Sélection des branches**, par (route, sens) :
   - candidat par `shape_id` = sa course la plus longue en nombre d'arrêts ;
   - candidats triés par nombre d'arrêts décroissant ;
   - on retient un candidat s'il apporte au moins un arrêt non encore couvert ;
   - on s'arrête quand l'union couvre tous les arrêts de la (route, sens).
5. **Passe 2 sur `stop_times.txt`** : ne matérialise que les lignes des courses retenues
   (915 au total).
6. `shapes.txt` : ne charge que les 37 `shape_id` retenus.
7. `stops.txt` : les arrêts référencés **et leurs stations parentes** (`location_type=1`).

Deux passes sur `stop_times.txt` au lieu d'une : le zip est déjà sur disque local, l'I/O est
peu coûteuse, et le pic mémoire devient **constant** quel que soit le nombre de lignes. La
lecture complète prend environ 2 min en Python ; c'est une fois par jour, hors chemin de
requête.

Le critère « couvrir tous les arrêts » remplace le critère arbitraire « garder le tracé le
plus long » et son commentaire d'excuse dans `buildLongestShape`. Il est **testable** : on
peut affirmer que la ligne 7 a 2 branches par sens et 0 arrêt non couvert.

### Nom des stations

Le nom vient aujourd'hui de `platforms.getFirst().getName()` — non déterministe, ticket
connu, et sur les correspondances (République, Châtelet) c'est un vrai bug dès qu'on a
16 lignes. Le loader persiste désormais les **stations parentes**, qui portent leur propre
nom et leurs propres coordonnées dans `stops.txt`. Le regroupement utilise le parent au lieu
d'un centroïde de quais : ticket réglé, et positionnement plus juste sur la carte.

Vérifié sur les données : les 781 quais du métro ont **tous** un `parent_station`, et les
321 stations correspondantes sont toutes présentes dans `stops.txt` en `location_type=1`. Le
repli sur le quai seul (arrêts sans parent) reste implémenté mais ne sert pas sur le métro.

### Schéma (migration V4)

Le modèle passe de « une route porte une géométrie » à « une route porte N branches ». Comme
seuls les parcours représentatifs sont conservés, `trip` n'est plus qu'une table de jonction
1:1 avec la branche et son `headsign` n'est jamais affiché — elle **disparaît**, et
`stop_time` s'accroche directement à la branche.

```sql
CREATE TABLE branch (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    route_id              UUID NOT NULL REFERENCES route(id),
    gtfs_shape_id         TEXT NOT NULL,
    representative_trip   TEXT NOT NULL,   -- traçabilité vers le GTFS
    direction             SMALLINT NOT NULL,
    terminus_name         TEXT,
    geom                  geometry(LineString, 4326) NOT NULL,
    UNIQUE (route_id, gtfs_shape_id, direction)
);
ALTER TABLE route ADD COLUMN mode TEXT, ADD COLUMN siri_line_ref TEXT;
ALTER TABLE route DROP COLUMN geom;          -- la géométrie vit désormais sur branch

-- stop_time s'accroche à branch et non plus à trip
DELETE FROM stop_time;                        -- régénéré au premier refresh
ALTER TABLE stop_time DROP COLUMN trip_id,
                      ADD COLUMN branch_id UUID NOT NULL REFERENCES branch(id);
ALTER TABLE stop_time DROP CONSTRAINT stop_time_trip_id_stop_sequence_key,
                      ADD UNIQUE (branch_id, stop_sequence);
DROP TABLE trip;

CREATE INDEX idx_branch_route ON branch (route_id);
CREATE INDEX idx_stop_time_branch ON stop_time (branch_id);
```

Migration destructrice : les données sont intégralement régénérées au refresh, déclenché au
démarrage (`initialDelay = 0`). Conséquence à assumer : une fenêtre de 404 entre la
migration et la fin du premier chargement.

### Modèle d'accès à la base — la sortir du chemin de requête

Point de design essentiel, contre-intuitif à la lecture du schéma : **aucun des trois
endpoints ne fait de requête SQL.** `/network`, `/vehicles` et `/stations/{id}/departures`
sont servis depuis le registry en mémoire et le snapshot temps réel. PostGIS n'est lu qu'à
deux moments : la réhydratation au démarrage, et le rebuild après le refresh quotidien.

Volumes après ce chantier :

| Table | Lignes | Taille |
|---|---|---|
| `route` | 16 | négligeable |
| `branch` | 37 | 8 110 points de tracé, ~130 Ko |
| `stop` | 1 102 (781 quais + 321 stations) | négligeable |
| `stop_time` | 915 | négligeable |

**~2 Mo pour toute la base.** À ces volumes PostgreSQL fait un seq scan et c'est plus rapide
qu'un parcours d'index. Les contraintes `UNIQUE` servent donc la **correction**
(déduplication au chargement), pas la vitesse ; les index de `branch(route_id)` et
`stop_time(branch_id)` ci-dessus sont posés par hygiène de FK, pas pour un gain mesurable.
Les deux index de la migration V3, ajoutés « en préparation du multi-ligne », deviennent
décoratifs — ils ne coûtent rien et restent en place plutôt que de créer du churn.

Deux conséquences à ne pas manquer :

- **Aucun index spatial GiST n'est nécessaire.** Une colonne `geometry` invite à en poser un,
  mais on ne fait aucune requête spatiale : la projection des arrêts sur le tracé
  (`LengthIndexedLine.project`) s'exécute en Java à la construction du registry — 915
  projections sur des tracés de ~229 points, quelques millisecondes.
- **Le risque réel est un N+1 à la réhydratation**, pas un index manquant. 37 branches × leurs
  `stop_time` × leurs `stop` en chargement paresseux, c'est une centaine de requêtes au
  démarrage. Deux requêtes à `JOIN FETCH` explicite suffisent, en reprenant le pattern déjà
  présent dans `findScheduleByRouteGtfsId`.

Pour mesurer le gain : avec le loader actuel, `stop_time` compterait **941 959 lignes** au
lieu de 915, soit plus de 100 Mo avec ses index — pour des données dont seules 915 sont lues.

Empreinte mémoire du registry, en regard : ~130 Ko de géométries, 915 `StopOnLine`, 321
stations, plus le snapshot temps réel (705 courses, ~3 000 appels). L'ensemble tient
largement sous quelques mégaoctets — c'est ce qui rend l'absence de cache de réponse
acceptable.

### Ingestion temps réel

On retire `?LineRef=` : flux global, un seul appel par poll, quota inchangé. Trois
changements dans `RealtimePoller`, tous nécessaires :

1. **`BodyHandlers.ofInputStream`** au lieu de `ofByteArray`, et parse Jackson en
   streaming : on avance jusqu'aux `EstimatedVehicleJourney` et on lit **une course à la
   fois** en sous-arbre, gardée si son `LineRef` est connu du registry. Pic mémoire = une
   course, au lieu de 45,6 Mo de `byte[]` plus l'arbre complet du `readTree` actuel.
2. **`Accept-Encoding: gzip`** et `GZIPInputStream` : mesuré à 3,96 Mo au lieu de 45,6 Mo,
   soit ~4,7 Go/jour au lieu de ~55 Go/jour.
3. **Timeout de requête relevé** (10 s aujourd'hui, insuffisant), en restant nettement sous
   l'intervalle de poll de 60 s.

`RtSnapshot.LiveJourney` gagne `recordedAt`. Le reste ne bouge pas : l'indexation par
LineRef existe déjà, la dégradation gracieuse en cas d'échec est déjà correcte, la fenêtre
de service métro reste valide.

### Moteur de positions

`PositionEngine.computeAll(TrackedLine, journeys, now)` :

1. arrêt imminent = premier encore à venir, sinon le dernier connu (inchangé) ;
2. **choix de la branche** : candidates = branches contenant cet arrêt (lookup O(1)),
   départagées par correspondance terminus / `DestinationName`. C'est exactement ce que fait
   déjà `pickDirection` ; on généralise de « les sens d'une ligne » à « les branches d'un
   sens » ;
3. interpolation sur le `LengthIndexedLine` **de la branche retenue** (inchangée par
   ailleurs : vraies heures si un arrêt passé est disponible, sinon segment théorique,
   fraction bornée [0,1]).

`Vehicle` gagne :

- `confidence` : `APPROXIMATE` si la course n'a qu'un seul appel, `RELIABLE` sinon. Signal
  purement structurel — il ne regarde aucune ETA, conformément à la décision produit.
  Concerne 36 % des courses.
- `recordedAt`, remonté du flux pour affichage informatif.

Les trains toujours non plaçables (0,6 %) sont comptés dans une métrique par ligne : la
dégradation résiduelle reste mesurable au lieu d'être silencieuse.

`Vehicle.tripId` est renommé `journeyRef` : le champ porte un `journeyRef` depuis toujours,
ticket MINOR ouvert.

## API

Les trois endpoints actuels sont préfixés `/lines/{id}/` alors que l'id est ignoré. Les
garder imposerait au front 16 appels de tracé et une déduplication des correspondances côté
client (République remonterait dans 5 payloads). Nouvelle surface :

**`GET /network`** — statique, cache 10 min.

```
{ lines:    [{ id, shortName, color, mode }],
  shapes:   [{ lineId, direction, terminusName, coordinates: [[lng,lat], ...] }],
  stations: [{ id, name, lat, lng, lineIds: [...] }] }
```

37 polylignes (8 110 points au total) et 321 stations dédupliquées côté serveur, en un appel.

**`GET /vehicles`** — tout le réseau suivi, un seul poll toutes les 4 s.

```
{ asOf, vehicles: [{ journeyRef, lineId, lat, lng, bearing, status,
                     headsign, nextStop, expectedTime, recordedAt, confidence }] }
```

~705 véhicules, soit ~140 Ko par réponse. On active `server.compression.enabled` : le JSON
descend sous 20 Ko.

**`GET /stations/{id}/departures`** — passages groupés **par ligne puis par direction**.
C'est ce que le multi-ligne rend naturel : sur une correspondance on veut toutes les lignes.
Mesuré : 61 stations sur 321 sont des correspondances, jusqu'à 5 lignes (République,
Châtelet).

```
{ stationName, lines: [{ lineId, shortName, color,
                         directions: [{ destination, passages: [{ journeyRef, expectedTime, status }] }] }] }
```

Trois précisions sur ce regroupement :

- **La fusion des deux sens est conservée.** La station résout tous ses quais via
  `parent_station`, donc les deux sens, et les passages sont regroupés par destination comme
  aujourd'hui. Une station affiche bien ses deux directions.
- L'imbrication `ligne → direction` corrige un bug latent : regrouper par destination *seule*
  à travers plusieurs lignes pourrait fusionner deux lignes partageant un nom de destination.
- **Sur une ligne à branches, une station du tronc commun affiche plus de deux groupes** —
  à Saint-Lazare la 13 montre « Asnières » et « Saint-Denis » séparément. C'est le
  comportement juste, et il ne devient exact que parce qu'on traite les branches.

On garde 3 passages par sens uniformément (jusqu'à 30 entrées à Châtelet, panneau défilant),
**lignes ordonnées par numéro**. L'ordre « par passage le plus imminent » est écarté : la
donnée se rafraîchit toutes les 4 s et le panneau se réordonnerait sous le curseur.

`/lines/{id}/shape`, `/lines/{id}/vehicles` et `/lines/{id}/stations/{sid}/departures`
disparaissent, ainsi que la constante `LINE_ID` du front. Rupture assumée : aucun
consommateur externe.

## Front

`useNetwork` remplace `useLineShape` : un fetch, puis deux sources GeoJSON pour tout le
réseau — `line-shapes` (37 features, `line-color: ["get","color"]`, une seule couche) et
`stops` (stations dédupliquées portant leurs `lineIds`). Le nombre de lignes n'ajoute plus
de couches.

### Véhicules

`VehicleLayer` dessine aujourd'hui une flèche sur canvas dans la couleur de la ligne,
réinjectée par `updateImage`. L'icône SDF avec `icon-color` est écartée : SDF est monochrome,
on perdrait le liseré blanc qui rend les flèches lisibles sur le fond de carte. À la place,
**une image par couleur distincte**, enregistrée à la volée, et `icon-image: ["get","icon"]`.
Quatorze images de 24×24 (deux paires de lignes partagent leur couleur), coût nul, rendu
identique.

Les trains `APPROXIMATE` passent en `icon-opacity` réduite ; le panneau indique une position
approximative et l'heure de `recordedAt`.

### Opacité des tracés — le seul effet de bord des branches

Deux branches d'une même ligne partagent leur tronc (sur la 7, ~15 km sur 21) : il est donc
dessiné deux fois, exactement superposé. Avec `line-opacity: 0.45`, l'opacité résultante
monte à ~0,70 et le tronc commun de la 7, de la 13 et de la 10 apparaîtrait **plus foncé**
que le reste du réseau — c'est visible.

Solution retenue : tracer en **opacité pleine avec une couleur éclaircie** — la couleur de
ligne mélangée à 55 % vers le blanc, soit exactement le rendu actuel, mais idempotent sous
superposition. Le découpage des troncs en segments uniques est écarté (beaucoup de code pour
un gain invisible), tout en restant la bonne réponse le jour du RER, où dix branches se
superposeront sur un tronc central.

### Filtre par ligne, légende, panneaux

La légende devient une liste de lignes avec bascule et compteur. Le filtre est purement
client, sans appel réseau : `setFilter` sur `line-shapes`, `vehicles` et les deux couches
d'anneaux. Pour les stations, un `setFilter` sur un tableau `lineIds` est malcommode en
expression MapLibre : on recalcule la `FeatureCollection` (≈300 features, trivial) et on
appelle `setData` sur les 321 features. Une station reste visible si au moins une de ses
lignes est active.

`StopPanel` groupe par ligne avec pastille de couleur, et affiche enfin un **badge de
retard** sur les passages `DELAYED` — transmis depuis toujours, jamais rendu (ticket MINOR).
Sélection et suivi ne changent pas, ils sont indexés par `journeyRef`.

### Charge de rendu

Les tracés ne sont pas un sujet : 8 110 points sur 37 polylignes, chargés une fois. Les 321
stations non plus, redessinées seulement au changement de filtre.

Le point de charge est la boucle de rendu, avec ~705 véhicules interpolés au lieu de ~50. La
boucle est déjà throttlée à ~15 fps avec culling viewport et arrêt à l'idle, et le snap
au-delà de 300 m est en place. Mais `render()` construit à chaque frame des features neuves
portant 7 propriétés dont 3 chaînes : ~10 600 objets par seconde à 705 véhicules. Deux
optimisations, toutes deux peu coûteuses :

- **Ne plus émettre par frame ce qui ne change jamais.** `headsign`, `nextStop`,
  `expectedTime` et `status` ne servent qu'au clic. Une `Map<journeyRef, V>` côté hook alimente
  le panneau, et la source ne porte plus que `journeyRef`, `bearing`, `icon`, `approximate`.
- **Réutiliser les objets features** entre les frames : une feature persistante par véhicule
  dont on mute les coordonnées en place, poussée par référence dans un tableau réutilisé.
  L'allocation par frame tombe à ~zéro.

Reste la sérialisation de 705 points par MapLibre à chaque `setData`, qui est son travail
normal. Marge disponible si nécessaire : `RENDER_INTERVAL_MS` peut passer à 100 ms — sur un
tween de 4 s ça laisse encore 40 pas pour un point qui avance lentement.

`promoteId` passe de `tripId` à `journeyRef` avec le renommage.

**Condition de fin du volet front : une mesure, pas une intuition.** Le plan doit inclure un
relevé du temps de frame à l'échelle de Paris, tous les véhicules visibles, avant de
considérer le front terminé. Les deux optimisations ci-dessus sont raisonnées, pas mesurées.

## Tests

`./mvnw verify` reste la vérification de référence.

**Unitaires**

- Découverte : filtre `route_type`, `exclude`, dérivation du LineRef, normalisation de l'id
  public (`3B` → `3b`).
- Couverture gloutonne : une ligne à deux branches → 2 tracés retenus et 0 arrêt non
  couvert ; un tracé inclus dans un autre → un seul retenu.
- Parse SIRI en streaming : fixture multi-lignes, lignes inconnues du registry écartées,
  `recordedAt` lu, corps gzippé décodé.
- `PositionEngine` : choix de branche par terminus, `confidence` à `APPROXIMATE` sur une
  course à un seul appel, lookup d'arrêt en O(1).
- Registry : réhydratation depuis la base, résolution id → ligne, 404 sur id inconnu.

**Intégration (Testcontainers)** — `gtfs-multi.zip` existe déjà et sera étendu à deux lignes
dont une à branche : assertions sur les branches persistées, la déduplication des stations,
le payload `/network`, `/vehicles` sur deux lignes, et `/stations/{id}/departures` groupé par
ligne sur une correspondance.

**Front** : pas de tests unitaires (convention du projet), `npm run build` fait office de
vérification.

## Limitations connues après ce chantier

- **36 % des courses n'ont qu'un seul `EstimatedCall`** et restent mal placées (le train est
  borné à l'arrêt précédant son unique appel, souvent un terminus lointain). Signalé par
  `confidence`, non corrigé. Piste à instruire séparément : remonter le long du tracé depuis
  l'arrêt connu en s'appuyant sur les durées théoriques inter-arrêts — un walk-back a déjà
  été tenté puis retiré (`ba58eeb`) parce qu'il provoquait des reculs, donc à reprendre avec
  précaution et sur ce seul sous-ensemble.
- **0,6 % de trains non plaçables** : `StopPointRef` SIRI absents du GTFS métro, sur la
  ligne 4. Problème de référentiel, pas de géométrie.
- **Deux paires de lignes partagent leur `route_color`** : la 13 et la 3bis (`#82C8E6`), la 6
  et la 7bis (`#82DC73`). Sur la carte, la couleur seule ne les distingue donc pas. En
  pratique l'ambiguïté se dissipe géographiquement — la 3bis (Gambetta ↔ Porte des Lilas) et
  la 7bis (Louis Blanc ↔ Pré-Saint-Gervais) sont des navettes courtes et isolées, situées
  hors du parcours de leur jumelle. Aucun traitement prévu ; à revoir si le tram entre dans
  le périmètre, car le T4 partage également `#82DC73`.
- Troncs communs dessinés en double (traité par l'opacité, pas par déduplication
  géométrique). Deviendra un vrai sujet au RER.
- L'étiquette « prochain arrêt » peut sauter une station absente du flux SIRI (cosmétique,
  position correcte).
- Aucun calendrier de service chargé : aucun horaire théorique daté n'est calculable. C'est
  déjà le cas aujourd'hui et ce chantier ne le change pas.

## Risques

- **La dérivation du LineRef est vérifiée sur un snapshot.** Si IDFM introduit une ligne au
  code atypique, elle apparaîtra à zéro train ; la gauge par ligne le rend visible, et la
  clé `exclude` permet de l'écarter en attendant.
- **Fenêtre de 404 au déploiement**, entre la migration V4 et la fin du premier chargement
  GTFS.
- **Durée du chargement** : deux passes sur 909 Mo. À mesurer en Java ; si c'est trop long au
  démarrage, l'option est de conserver le référentiel en base entre deux refresh (ce que
  permet déjà la réhydratation du registry) et de ne recharger qu'en tâche de fond.
