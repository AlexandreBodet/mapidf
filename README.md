# MapIDF — suivi temps réel du métro parisien

## Démarrage
1. `cp .env.example .env`, puis renseigner `PRIM_API_KEY` et `POSTGRES_PASSWORD`
2. `docker compose up --build`
3. Front : http://localhost:8080 — API : http://localhost:8100/api/network
4. Santé backend : http://localhost:9100/actuator/health — mesures brutes sur
   `/actuator/metrics` et `/actuator/prometheus` (port publié sur la loopback seulement)

Le `.env` (gitignoré) est la **seule** source des identifiants : aucun n'a de valeur par défaut
dans le code, pour qu'aucun ne puisse suivre le projet jusqu'en production. Les trois chemins de
lancement le lisent — `docker compose` nativement, IntelliJ via sa configuration de run, et
`./mvnw spring-boot:run` via `spring.config.import` dans
[application.yml](backend/src/main/resources/application.yml). Une configuration incomplète
**empêche le démarrage**, en nommant les variables manquantes : c'est le rôle de
`ConfigurationGuard`, car Spring seul ne lèverait pas — le Binder garde le texte littéral
(`${POSTGRES_PASSWORD}`) et l'application démarrerait à moitié.

## Développement
- Backend : `cd backend && ./mvnw spring-boot:run` (API :8100, Actuator :9100)
- Base seule (backend hors Docker) : `cd backend && docker compose up -d`
- Front : `cd frontend && npm run dev` (proxy /api → :8100) — **Node 24 LTS** (épinglé par Volta
  dans `frontend/package.json`, et c'est la base de l'image Docker). Vite 8 exige
  `^20.19.0 || >=22.12.0`, mais **rien ne l'impose à l'installation** : sur un Node plus ancien,
  `npm install` se contente d'un `npm warn EBADENGINE` et **réussit** — la panne arrive plus tard,
  au premier `npm run dev` ou `npm run build`, sous forme d'erreur de syntaxe ou d'API manquante
  dans vite. Si un `npm run build` casse sans raison sur un poste, `node -v` est le premier réflexe.
  Node 26 est délibérément écartée tant qu'elle n'est pas LTS (cf. QUA-9 dans la
  [feuille de route](docs/roadmap.md))
- Tests : `cd backend && ./mvnw test` (tests unitaires seuls, rapide) — mais la vérification de
  référence du projet est `cd backend && ./mvnw verify` (build complet + tests d'intégration
  Testcontainers ; nécessite Docker).
- Lint front : `cd frontend && npm run lint` (ESLint, muet attendu) — base du chantier QUA-8
  (cf. [feuille de route](docs/roadmap.md)).

### Ports : rien à configurer

L'API écoute sur **8100** et l'Actuator sur **9100**, et non sur 8000/9000 : ce sont les ports de
la moitié des projets Spring, et un dev qui en fait tourner un autre ne pouvait pas démarrer
celui-ci. Avec ces valeurs, un clone tourne sans qu'on ait rien à régler.

S'il faut malgré tout les déplacer, **deux lignes dans le `.env`** suffisent — le `.env` reste la
source unique, et les trois chemins de lancement la lisent (Docker, IntelliJ, `spring-boot:run`),
le proxy du front compris :

```properties
SERVER_PORT=8200
MANAGEMENT_SERVER_PORT=9200
```

Dans la pile Docker, ces valeurs ne changent que les ports **publiés** sur la machine — et
depuis SEC-3, l'API (8100) rejoint l'Actuator (9100) : **les deux ne sont publiés que sur
`127.0.0.1`**. Les variables continuent de choisir le port d'hôte, seule l'interface d'écoute
change. Le conteneur, lui, garde 8100/9100 en dur (`environment` dans le compose), parce que
nginx y proxifie vers `backend:8100`. Déplacer ses ports d'hôte ne peut donc pas casser `/api`.

## Premier démarrage
À la première exécution (base vide, ou après une migration Flyway), le backend télécharge le
GTFS IDFM complet (~125 Mo, mesuré le 2026-07-30) et le charge avant que la carte n'ait quoi
que ce soit à afficher. Pendant ce temps, `GET /api/network` répond **200 avec un réseau vide** (pas 404) : ce n'est pas
une panne. Le front l'affiche désormais explicitement (« Plan en préparation ») et réessaie
toutes les 10 s, au lieu de rester sur une carte vide jusqu'à un rechargement manuel. La
progression se suit dans les logs backend : `[GTFS] N ligne(s) découverte(s) pour
les modes [...]`, puis par ligne `[GTFS] ligne X (nom) : N candidate(s) → M branche(s)
retenue(s)`, puis `[GTFS] N route(s), M branche(s), P stop_time(s) persistés`, et enfin
`[REGISTRY] N ligne(s), M branche(s), P station(s)` une fois le réseau prêt à être servi.

## Configuration
Le périmètre suivi est le métro complet, découvert automatiquement par mode GTFS via
`app.network.modes` (route_id à exclure via `app.network.exclude`) — il n'y a plus de
ligne unique configurée à la main, ni de `LINE_ID` côté frontend : le front charge le
réseau dynamiquement via `GET /api/network`.

Le mode `METRO` est **pré-configuré** dans `application.yml` (`app.network.modes:
[METRO]`, et `gtfs-static-url` = GTFS IDFM complet ~125 Mo, filtré en streaming par le
backend pour ne garder que les lignes du ou des modes suivis). Rien d'autre à configurer que
le `.env` décrit plus haut (`PRIM_API_KEY` + `POSTGRES_PASSWORD`). Pour suivre d'autres
modes (ex. tram), ajustez `app.network.modes` (et le `gtfs-static-url` si besoin).

## API
- `GET /api/network` — lignes, branches et tracés du réseau suivi.
- `GET /api/vehicles` — positions courantes des véhicules (tous modes/lignes suivis).
- `GET /api/stations/{id}/departures` — prochains passages à une station, groupés par ligne,
  et les perturbations visant ses quais.
- `GET /api/disruptions` — perturbations **en cours** des lignes suivies (les travaux à venir
  sont écartés), avec la pire gravité par ligne et les stations dont un quai est touché.

Les quatre endpoints sont limités à **600 requêtes par minute et par adresse IP**
(`app.ratelimit.requests-per-minute`) ; au-delà, la réponse est un **429** portant `Retry-After`
et le corps d'erreur habituel. La loopback n'est pas comptée. Un client normal consomme ~31
req/min par onglet en pointe (cf. [feuille de route](docs/roadmap.md), SEC-3) : il ne l'atteint
jamais.

## Utilisation — échelle de zoom
La carte se peuple progressivement avec le zoom : les tracés des lignes sont visibles à tout
niveau, les stations apparaissent à partir du zoom 11, les trains à partir du zoom 12 (zoom
d'ouverture de la carte) et les noms de stations à partir du zoom 13. En dessous de 12, aucun
train n'est affiché même si des courses circulent.

Le sélecteur de lignes (bas de l'écran) fonctionne par isolement : un premier clic sur une ligne
isole cette ligne seule ; les clics suivants ajoutent ou retirent des lignes du sous-ensemble
affiché, sans jamais le vider complètement ; « tout afficher » revient à toutes les lignes. Les
pastilles de ligne affichées dans la fiche d'une station isolent elles aussi la ligne cliquée,
quel que soit le sous-ensemble affiché auparavant.

Sous 720 px de large, les panneaux flottants sont remplacés par une **feuille repliable** en bas
d'écran : on la tire par sa poignée, ou on la touche pour passer au cran suivant (aperçu → moitié
→ plein → aperçu). Un glissement vers le bas replie la feuille depuis **n'importe où** dans son
corps, pas seulement la poignée — sauf si ce corps a du contenu à défiler vers le haut, auquel cas
le défilement l'emporte tant qu'on n'est pas déjà remonté en haut. Au cran aperçu, la feuille ne
montre plus que sa poignée (le résumé et le pied de licence se replient avec elle ; l'heure du
dernier instantané reste lisible sur la poignée). Elle porte le sélecteur de lignes par défaut ;
ouvrir une station ou un train y affiche sa fiche, et la fermer ramène le sélecteur. Sur ces
largeurs, la mention de source de la carte passe derrière un « ⓘ » en haut à droite.

En paysage (largeur au-dessus de 720 px mais hauteur réduite), la carte du sélecteur de lignes en
bas à gauche peut se replier via un chevron `▾`/`▸` à côté du résumé — masque la grille des 16
pastilles et la liste des perturbations sans toucher au compteur de trains. Ce chevron n'existe que
sur cette carte flottante, jamais sur la feuille étroite.

## Mise en ligne : ce que doit faire un terminateur TLS

La pile ne termine pas le TLS : elle est faite pour être placée **derrière** un terminateur
(reverse proxy, ingress, tunnel), qui reste à choisir avec l'hébergeur. Ce qui est déjà prêt de
notre côté : nginx émet tous les en-têtes de sécurité (dont HSTS, inactif tant que l'origine est
en `http:`) et relaie `X-Forwarded-For`/`X-Forwarded-Proto` au backend.

Ce que le terminateur doit faire, et que rien ici ne peut faire à sa place :

1. Terminer le TLS et rediriger 80 → 443.
2. Transmettre `X-Forwarded-Proto: https`.
3. **Ne pas router `/actuator`.** La pile ne le publie que sur la loopback de l'hôte ; un proxy
   trop généreux annulerait ce garde-fou et exposerait la version de PostgreSQL, l'URL JDBC et
   les internes de la JVM.
4. **Le port API** (8100 par défaut) — **fermé sur la loopback dans le compose racine depuis
   SEC-3**, comme l'Actuator. Sur un déploiement qui ne repart pas de ce compose (jar nu, autre
   orchestrateur), refaire la même restriction : un accès direct à ce port contournerait nginx et
   tous ses en-têtes de sécurité.
5. Laisser passer les en-têtes de réponse de nginx sans les réécrire — c'est à ce moment-là que
   HSTS devient actif, sans changement de configuration.

Aucune question de CORS ne se pose : une seule origine sert l'application et l'API.

Les en-têtes servis se vérifient sur une pile lancée :

```bash
scripts/check-headers.sh                      # http://localhost:8080 par défaut
scripts/check-headers.sh https://exemple.fr   # ou une instance déployée
```

Le détail de chaque directive de la CSP, et la mesure qui la justifie, sont dans la
[spec SEC-4](docs/superpowers/specs/2026-07-31-sec-4-entetes-securite-tls-design.md).

## Données, sources et licences

Le code de MapIDF est sous [licence MIT](LICENSE). Les **données ne le sont pas** : elles
appartiennent à leurs producteurs et gardent leurs propres conditions.

| Source | Ce qu'on en tire | Licence |
|---|---|---|
| [Réseaux urbains et interurbains d'Île-de-France Mobilités](https://transport.data.gouv.fr/datasets/reseau-urbain-et-interurbain-dile-de-france-mobilites) — GTFS statique + SIRI Lite via PRIM | tracés, arrêts, couleurs de ligne, horaires temps réel | [Licence Mobilité](https://cloud.fabmob.io/s/eYWWJBdM3fQiFNm) (v. 03.02.2021) |
| [OpenFreeMap](https://openfreemap.org) / [OpenMapTiles](https://www.openmaptiles.org) / [OpenStreetMap](https://www.openstreetmap.org/copyright) | fond de carte (rues, POI) | ODbL (données OSM), attribution fournie par la TileJSON |
| Natural Earth (relief basse résolution du style Liberty) | ombrage aux zooms lointains | domaine public |

Ce n'est **pas** de l'ODbL : les données IDFM sont sous *Licence Mobilité*, un texte proche
mais distinct, avec des clauses propres (art. 5.2 compatibilité avec la stratégie de mobilité,
5.7 neutralité et loyauté, 5.6.d re-partage sur le Point d'Accès National).

### Obligations tenues dans le code — ne pas les retirer

- **Mention de la source (art. 5.4)** : son texte vit dans
  [attribution.ts](frontend/src/map/attribution.ts), posé en `customAttribution` par
  [MapView.tsx](frontend/src/map/MapView.tsx). Dépliée au-dessus de 720 px, au lieu d'être
  repliée derrière le bouton « ⓘ » (défaut MapLibre) ; en dessous, repliée **et** remontée en
  haut à droite, faute de quoi elle se poserait par-dessus la feuille. Exception bornée,
  détaillée dans [CLAUDE.md](CLAUDE.md).
- **Neutralité et loyauté (art. 5.7)** : au-dessus de 720 px,
  [SheetFooter.tsx](frontend/src/ui/SheetFooter.tsx) porte les deux obligations — la position est
  **estimée** (le métro n'a pas de GPS) et l'**heure du dernier snapshot**. En dessous, la feuille
  se replie au cran aperçu jusqu'à ne plus montrer que sa poignée : la nature estimée rejoint alors
  la mention de source derrière le « ⓘ » (`ESTIMATION_NOTICE` dans `attribution.ts`), et la
  fraîcheur de la donnée s'affiche directement sur la poignée
  ([Sheet.tsx](frontend/src/ui/Sheet.tsx)) — le contenu de l'attribution MapLibre se fige à la
  construction du contrôle et ne peut pas suivre chaque nouvel instantané.
- **Clé PRIM personnelle (art. 4.1)** : `PRIM_API_KEY` vit dans `.env` (gitignoré), ne sort
  jamais du backend et n'est jamais exposée au frontend.

### À traiter avant tout déploiement public

L'usage strictement interne n'est pas une « utilisation publique » au sens de l'art. 5.6.c :
les obligations ci-dessous ne se déclenchent qu'à l'exposition de l'appli hors de la machine
de dev.

- **Partage à l'identique (art. 5.5 et 5.6.d)** : le PostGIS peuplé par `GtfsStaticLoader` est
  une base de données dérivée. Servir directement ses tracés et arrêts via `/api/network`
  ouvert au public peut faire tomber l'art. 5.6.d (publication de la base dérivée sur
  transport.data.gouv.fr, sous le jeu initial et au format d'origine). L'art. 5.6.b (utiliser
  la base pour produire une « Création » n'engendre pas de base dérivée) est l'argument
  inverse, mais il s'affaiblit à mesure que l'API expose la donnée brute.
- **CGU de PRIM et « Chartes et prescriptions » IDFM** : non vérifiées ici (le site PRIM
  renvoie 403 derrière Cloudflare hors navigateur). Deux points y sont traités et échappent
  à la vérification faite dans ce repo — les quotas et l'éventuelle compensation financière
  (l'art. 4.3 de la licence renvoie aux CGU de la plateforme), et l'usage des **marques** :
  la Licence Mobilité exclut de son périmètre « toute marque déposée associée à la Base de
  données », ce qui couvre indices de ligne, couleurs officielles et logos. À lire depuis un
  navigateur avant publication.
