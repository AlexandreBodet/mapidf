# CLAUDE.md — guide de travail pour l'IA sur MapIDF

Ce fichier est chargé automatiquement au début de chaque session Claude Code dans ce
repo. Il complète le [README](README.md) (qui explique *comment lancer*) en décrivant
*comment travailler ici* : conventions, vérifications, et surtout les pièges non-évidents
qui nous ont déjà coûté du temps. Garde-le **concis** — il coûte du contexte à chaque
session. Pour le détail, suis les liens vers les docs.

## En deux mots

Appli perso de **suivi temps réel des transports d'Île-de-France sur une carte**.
Périmètre = **le métro complet (16 lignes)**, découvert automatiquement par mode GTFS —
ce n'est plus la seule ligne 9 du MVP initial. Backend Spring Boot (proxy PRIM + moteur de
positions) + PostGIS ; frontend React + MapLibre GL qui poll le backend toutes les ~4 s et
interpole les positions au `requestAnimationFrame`.

Le métro n'a **pas de GPS** : les positions sont **estimées** par interpolation à partir
des horaires temps réel SIRI (prochain arrêt + heure estimée), pas mesurées.

## Commandes

```bash
# Backend (depuis backend/)
./mvnw verify          # build + tous les tests, DONT les IT Testcontainers — la vérif de référence
./mvnw test            # tests unitaires seuls (plus rapide)
./mvnw spring-boot:run # API :8100 (context-path /api), Actuator :9100

# Frontend (depuis frontend/)
npm run dev            # Vite, proxy /api → :8100
npm run build          # build de prod — sert de vérif (pas de tests unitaires front)

# Tout en Docker (depuis la racine)
docker compose up --build   # front :8080, api :8100, actuator :9100
```

**Cycle de vie des apps** : ne suppose pas que le backend/front/Docker sont à toi à
démarrer ou arrêter — demande, ou vérifie, avant. Certains devs les gèrent via leur IDE.

## Conventions de code

- **Spring Boot 4.1 / Java 25 / Lombok.** Records pour les DTO immuables.
- **Jackson 3** (`tools.jackson.databind`, pas `com.fasterxml`). Sur un `JsonNode`,
  utilise **`.asString()`**, pas `.asText()` (qui n'existe plus).
- **TDD** : écris le test qui échoue avant l'implémentation (cf. skill superpowers).
- Conventions Java/Spring maison : voir le projet de référence Steamulo.
- **Migrations Flyway : une migration déjà appliquée ne se modifie JAMAIS**, pas même un
  commentaire — le checksum change et Flyway refuse de démarrer sur toute base qui portait
  l'ancienne version. Ce qui a évolué depuis se documente **près du code**, pas dans le `.sql` ;
  un correctif se fait par une **nouvelle** migration. Les IT (Testcontainers, base vide à chaque
  run) ne peuvent pas détecter cette faute : V3 s'y applique fraîche et valide toujours.
- Secrets : `PRIM_API_KEY` vit dans **`.env` (gitignoré) — à ne JAMAIS commiter.**
  `.env.example` documente les variables attendues.
- **Licence des données : *Licence Mobilité*, pas ODbL.** Deux obligations (art. 5.4 source,
  5.7 neutralité/loyauté) qui ne sont pas cosmétiques — **ne pas les retirer**, où qu'elles
  vivent. La mention de source (`customAttribution` dans `map/attribution.ts`, posée par
  `MapView.tsx`) reste dépliée en bas à droite au-dessus de 720 px ; **sous 720 px**, elle passe
  en `compact` **et** en `top-right` (les recommandations OSM tolèrent le repli sur écran
  contraint, pas sur une carte plein écran ; remonter le bouton est indispensable, sinon replié en
  bas à droite il se poserait par-dessus le contenu de la feuille — les conteneurs de contrôles
  MapLibre portent `z-index: 2`). La nature estimée et l'heure du snapshot (« position estimée » +
  heure) vivent, au-dessus de 720 px, dans le pied de `SheetFooter.tsx` (et non plus du
  `LinePicker`, dont il a été extrait) ; **sous 720 px**, la feuille se replie au cran `apercu`
  jusqu'à ne plus montrer que sa poignée, donc la nature estimée rejoint la mention de source
  derrière le « ⓘ » (`ESTIMATION_NOTICE` dans `attribution.ts`) et la fraîcheur s'affiche
  directement sur la poignée (`Sheet.tsx`, prop `asOf`) — le texte d'attribution de MapLibre se
  fige à la construction du contrôle et ne peut pas suivre chaque nouvel instantané. Ce qui reste
  à trancher avant un déploiement public (partage à l'identique de la base dérivée, CGU PRIM,
  marques) est listé dans la section « Données, sources et licences » du [README](README.md).
- **Front : jamais de `feature-state` sur la couche `vehicles`** (`VehicleLayer.ts`) — les deux
  anneaux (halo de sélection, surlignage) sont pilotés par `setFilter` sur la propriété
  `journeyRef`, pas par `feature-state` (pourtant l'approche idiomatique, essayée deux fois) :
  à ~15 `setData`/s sur ~705 features, `initializeTileState` finissait par lever « feature index
  out of bounds » en boucle. Détail complet dans le commentaire d'en-tête de `journeyRefFilter`.

## Configuration du réseau suivi

Le périmètre est piloté par `app.network.modes` (liste de `TransportMode`, ex. `[METRO]`)
et `app.network.exclude` (route_id à écarter) dans
[application.yml](backend/src/main/resources/application.yml). Les lignes ne sont **pas**
listées à la main : `GtfsStaticLoader.discoverLines` parcourt `routes.txt`, dérive le mode
depuis `route_type`, et ne retient une route que si son mode est suivi et qu'elle n'est pas
exclue. Le GTFS IDFM complet (~125 Mo) reste filtré **en streaming** par le loader, mais sur
tout ce périmètre (plus une seule ligne cible). Le front n'a **plus de `LINE_ID`** : il
charge le réseau dynamiquement via `GET /network`, il n'y a plus de résolution de ligne
côté URL.

La spec écarte volontairement toute config par ligne (seuils, overrides) au profit d'un
**garde-fou observable** : `RealtimePoller` publie la jauge `mapidf.rt.journeys` (tag `line`,
courses SIRI retenues par ligne suivie — y compris à zéro) et `PositionEngine` incrémente deux
compteurs tagués `line` : `mapidf.position.unplaced` (cf. limitations) et
`mapidf.position.branch.unresolved` (arrêt imminent présent sur plusieurs branches sans terminus
correspondant à la destination SIRI). Une ligne qui dégrade se voit dans ces métriques ; le
remède est `app.network.exclude`, pas un seuil de tolérance.

Ces mesures ne servaient à rien tant qu'il fallait penser à les lire : `LineCoverageGuard`
journalise désormais un WARN quand une ligne suivie reste **15 min sans aucune course alors que
le reste du réseau circule** (réseau entier à zéro = panne du flux, pas d'une ligne → aucun
avertissement, le compteur d'échecs de poll le dit déjà). Et `/actuator/prometheus` expose toutes
les mesures pour un collecteur, s'il y en a un jour.

## Données temps réel — pièges à connaître (IMPORTANT)

La source est le endpoint SIRI-ET **`estimated-timetable`** de PRIM (en-tête `apikey`).
Un seul appel couvre **tout le réseau**, désormais servi **en gzip** (45,6 Mo → 3,96 Mo ;
le `HttpClient` Java ne le négocie pas seul, il faut poser `Accept-Encoding: gzip` et
décompresser soi-même) et **parsé en streaming**. Le coût quota est indépendant du nombre
de lignes. Détails et structure exacte : [backend/docs/prim-integration.md](backend/docs/prim-integration.md).

Ce qui n'est **pas** intuitif dans le flux, et qui a déjà causé des bugs :

- **Les `EstimatedCall` ne sont PAS triés** (pas de champ `Order`, 1 à 22 appels par
  course). Ne jamais prendre `path(0)` comme prochain arrêt → prendre le **plus tôt à
  venir**. C'est ce que fait `PositionEngine`.
- **Aucun `RecordedCalls`** : les arrêts passés sont absents. Un train en marche a donc
  souvent **tous ses appels dans le futur** ; ne pas en conclure qu'il n'est pas parti.
- `OriginRef` est **présent comme clé mais toujours vide** (`{}`), pas `null` — inexploitable
  dans les deux cas. `DatedVehicleJourneyRef`, en revanche, **est toujours renseigné pour le
  métro** (705/705 courses mesurées) : l'identité d'un train est stable entre deux polls, le
  repli composite de `RealtimePoller` ne sert jamais pour ce mode.
- `RecordedAtTime` (horodatage de dernière mise à jour de la course) est capté mais **n'est
  pas un signal de perturbation** : mesuré en pleine perturbation ligne 8, cette ligne avait
  la donnée la plus fraîche du réseau. Une perturbation se lit dans `DepartureStatus:
  DELAYED`, pas dans la fraîcheur de `RecordedAtTime`.
- ~1/3 des courses n'ont qu'un seul appel (terminus lointain) → mal plaçables (signalé par
  `confidence`, voir limitations ci-dessous).
- **Perturbations : source = `disruptions_bulk`, pas SIRI `general-message`** (mesuré le
  2026-07-30 : ce dernier renvoie `InfoMessage: []` pour le métro et exige un paramètre par
  ligne). Un appel couvre tout le réseau, gzip obligatoire (1,53 Mo → 288 Ko).
- **Le champ `message` d'une perturbation est du HTML tiers** : `DisruptionPoller.toPlainText`
  le réduit en texte (balises retirées, entités décodées) avant qu'il ne sorte de l'API — le
  rendre en HTML serait la faille. Il est indispensable : mesuré, un « Information - Autre »
  n'a de sens que dans ce message.
- **La plupart des perturbations sont des travaux futurs** (4 en cours sur 15 au moment de la
  mesure) : filtrer sur `applicationPeriods` couvrant l'instant, sinon on annonce une ligne
  coupée trois semaines à l'avance. Ce filtre ne contredit pas la décision ci-dessous : il ne
  masque aucun train.
- **Décision produit ferme : PAS de seuil d'ETA pour masquer un train.** Un seuil ferait
  disparaître les trains lors des perturbations de trafic — exactement ce qu'on veut voir.
  Tout filtrage doit s'appuyer sur un **signal non temporel** (fiabilité du placement).

## Où sont les décisions et l'historique

- **Chantiers à venir (sécurité, UX, perf, légal, produit)** : [docs/roadmap.md](docs/roadmap.md)
  — la liste de référence, avec un identifiant et un statut par chantier. À mettre à jour au fil
  de l'avancement ; c'est là qu'on note ce qu'on n'attaque pas tout de suite, et pourquoi.
- **Specs & plans par feature** : [docs/superpowers/specs/](docs/superpowers/specs/) et
  [docs/superpowers/plans/](docs/superpowers/plans/).
- **Intégration PRIM (structure des données, quotas, choix)** :
  [backend/docs/prim-integration.md](backend/docs/prim-integration.md).
- **Journal de décisions / tickets post-MVP** : `.superpowers/sdd/<nom-du-plan>/progress.md`
  — un journal par plan (ex. `2026-07-29-multi-ligne-metro/progress.md` pour ce chantier),
  ⚠️ gitignoré, présent seulement en local.

## Limitations connues (ne pas re-débugguer sans lire d'abord)

- **Courses à un seul appel = terminus lointain** (~1/3 du flux) : désormais **signalées
  par `confidence: APPROXIMATE`** sur le véhicule (opacité réduite côté front), pas
  corrigées — le train reste rendu avec sa position bornée à l'arrêt précédent. À traiter
  un jour par un signal non temporel — **jamais** par un seuil d'ETA (cf. décision
  ci-dessus).
- **~0,6 % des trains métro ne sont pas plaçables** (aucune branche ne contient l'arrêt
  imminent, après couverture gloutonne des tracés) : exclus du résultat de `/vehicles`,
  comptés par la métrique `mapidf.position.unplaced` (taggée par ligne).
- **Couleurs partagées entre lignes** : 13/3bis (`#82C8E6`) et 6/7bis (`#82DC73`, aussi
  celle du T4) — aucune distinction visuelle entre ces paires sur la carte.
- **Aucun calendrier de service chargé** (`calendar.txt`/`calendar_dates.txt`) : le GTFS
  statique ne répond pas à un horaire théorique daté, seulement à l'ordre et l'espacement
  des arrêts par branche.
- L'étiquette « prochain arrêt » peut sauter une station absente du flux SIRI : c'est
  cosmétique (trou de données), la position reste correcte.
- **Feuille repliable sous 720 px** (largeur seule : un téléphone en paysage garde les cartes
  flottantes, une feuille sur 390 px de haut serait pire que le mal). La feuille flotte au-dessus
  d'une carte qui garde tout le viewport ; c'est `map.setPadding` qui décale les recentrages, pas
  une mise en colonne — donc jamais de `map.resize()`. Le cran `moitié` laisse du blanc sous un
  contenu court : prix assumé d'un repère stable.
