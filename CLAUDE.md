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
npm run build          # build de prod — sert aussi de vérif de typage
npm test               # Vitest : fonctions pures en Node, composants en jsdom
npm run lint           # ESLint (config minimale, cf. QUA-5) — muet attendu, base de QUA-8

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
- **MapLibre 6 n'émet plus son worker tout seul** : il en dérive l'URL depuis `import.meta.url`, ce
  que le bundling casse. Sans le `setWorkerUrl` de `MapView.tsx` (import `?worker&url`), **le build
  est vert, sans un avertissement, et `dist/` ne contient aucun worker** — carte blanche en prod sur
  un 404 `/assets/maplibre-gl-worker.mjs`. Aucun test ne monte MapLibre : le seul contrôle est un
  navigateur, ou un `ls dist/assets/maplibre-gl-worker-*.js`. Conséquence CSP détaillée dans
  l'en-tête de `security-headers.conf` ; ce qui ne s'y lit pas : **la chaîne CSP y est dupliquée avec
  `scripts/check-headers.sh`**, et n'en toucher qu'une fait rougir le script sur un écart de texte,
  pas sur un défaut.
- **nginx : `add_header` n'est PAS hérité** dès qu'un bloc `location` en pose un lui-même. C'est
  pourquoi les en-têtes de sécurité vivent dans `frontend/security-headers.conf`, **inclus par
  chaque `location`** : les poser une seule fois au niveau `server` les ferait disparaître des
  réponses de `/assets/` (qui a son propre `Cache-Control`), silencieusement. Et pas de
  `Cache-Control` dans `/api/` : `add_header` ajoute au lieu de remplacer, il doublerait celui du
  backend sur `/network`. Un **quatrième** `include`, au niveau `server`, couvre les erreurs émises
  avant tout choix de `location` (mesuré : un 400 sur en-tête surdimensionné sortait nu) — et c'est
  lui le vrai filet : depuis, un `location` privé de son `include` **hérite** des en-têtes, donc
  `scripts/check-headers.sh` constate qu'ils sont bien servis sur les quatre chemins (dont un 404 et
  `/api/`) sans pouvoir distinguer « posé ici » d'« hérité ». Toute nouvelle origine
  externe côté front doit être déclarée dans `security-headers.conf`, faute de quoi la ressource
  sera bloquée — et ça ne se voit ni au `npm run build`, ni avec `npm run dev` (sans CSP),
  seulement dans un navigateur sur la pile Docker.
- **`proxy_pass http://backend:8100;` sans slash final est volontaire** : il transmet l'URI
  complète, `/api` étant le context-path du backend. Le « corriger » casse tous les appels.
- **Tests front : l'environnement est déclaré par fichier**, pas globalement — un
  `// @vitest-environment jsdom` en première ligne des tests de composants, rien pour les
  fonctions pures, qui restent en Node et rapides. `src/test/setup.ts` porte les trois stubs que
  jsdom impose encore en 27 (pas de `ResizeObserver`, pas de `setPointerCapture`, toute mesure à
  0) ; son garde `typeof Element !== "undefined"` est indispensable, ce fichier étant chargé aussi
  pour les tests qui tournent en Node. La **vitesse d'un geste est testable** : `Sheet.test.tsx`
  construit l'événement à la main (`firePointer`, un `MouseEvent` typé) pour maîtriser son
  `timeStamp` — hérité de jsdom 26 sans `PointerEvent` global, gardé bien que jsdom 27 en fournisse
  un (cf. QUA-8). **Piège toujours vrai : `timeStamp: 0` ne marche pas**, React calculant
  `event.timeStamp || Date.now()`.
- **`npm outdated` sous-déclare le retard** (mesuré le 2026-08-11) : `vite` plafonné à 6.4.3 quand
  son dist-tag `latest` était **8.2.1**, `@vitejs/plugin-react` omis **entièrement** (4.7.0 contre
  6.0.5) — `--prefer-online` n'y change rien ; le contrôle fiable est `npm view <paquet> dist-tags`.
  Côté Maven, rien de tel : le versions-plugin agrège fidèlement tous les dépôts configurés — mais un
  dépôt **interne** peut y republier un artefact ancien et se déclarer `<latest>` (`commons-csv
  20110211`, un jar de 2011), d'où le croisement avec le `maven-metadata.xml` de Central.
- **TypeScript est en 6.0.3 délibérément, ne pas « monter » en 7.** `typescript-eslint` porte un
  garde `if (versionMajor >= 7) throw` : en TS 7, `npm run lint` ne rougit pas, il **s'interrompt**.
  Or `latest` pointe la 7, donc `npm outdated` proposera cette montée en boucle — c'est un piège,
  pas un retard (suivi amont dans QUA-10). Corollaire de TS 6 : les `@types/*` non importés ne sont
  plus inclus automatiquement, d'où les imports explicites depuis `"geojson"` dans `VehicleLayer.ts`
  et `App.tsx` — et surtout **pas** de `"types": [...]` en tsconfig, qui masquerait en silence tout
  futur `@types/x`.

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
- **Hors des heures de service (05h30–03h00), le snapshot est oublié, pas seulement figé.**
  `PositionEngine` place au dernier arrêt connu quand tous les appels sont passés (« on n'exclut
  jamais un train qui a des données ») : garder le snapshot de 02h59 peuplait la carte nocturne de
  ~705 courses immobiles à leur terminus. `RealtimePoller.tick` remet donc l'instantané à vide, et
  `/vehicles` porte `inService` pour que le front distingue la nuit d'une panne. `LineCoverageGuard`
  a sa **propre** fenêtre (06h30–00h30), plus étroite : dans la queue de service les lignes
  s'éteignent une à une, un zéro isolé n'y veut rien dire.
- **`/vehicles.asOf` est la date de la DONNÉE, pas celle de la requête** (`RtSnapshot.dataDate`,
  `null` avant le premier poll). C'était l'inverse, et le pied de page tamponnait l'heure courante
  sur un instantané vieux de plusieurs heures — l'art. 5.7 interdit d'induire en erreur sur la date
  de mise à jour autant que sur le contenu.
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
  flottantes, une feuille sur 390 px de haut serait pire que le mal). Elle flotte au-dessus d'une
  carte qui garde tout le viewport ; c'est `map.setPadding` qui décale les recentrages, pas une
  mise en colonne — donc jamais de `map.resize()`. Trois conséquences assumées : le cran `moitié`
  laisse du blanc sous un contenu court (prix d'un repère stable) ; **au cran `plein` le suivi
  d'un train passe derrière la feuille**, le padding de caméra étant plafonné à 45 % de la
  hauteur (le réduire ne déplacerait que l'absurdité) ; et l'aperçu n'est la poignée seule que
  sans fiche ouverte — avec une fiche il porte son titre et son `✕`, pour qu'on puisse la
  refermer sans déplier.
- **`--tap` ne touche que nos composants** : les contrôles MapLibre (zoom, boussole, et le `ⓘ`
  de l'attribution) restent sous les 44 px tactiles, et peuvent chevaucher le bandeau d'état sous
  384 px de large. Corriger demanderait une règle CSS visant leurs classes, hors du style inline
  du projet.
