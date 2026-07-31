# Feuille de route — chantiers identifiés

État des lieux dressé le **2026-07-30**, juste après le merge du suivi multi-lignes métro
(16 lignes) et de la mise en conformité Licence Mobilité. Ce fichier liste ce qui manque au
projet et dans quel ordre l'attaquer — il ne décrit **pas** comment le faire (ça reste le rôle
d'une spec dans [superpowers/specs/](superpowers/specs/)).

## Comment tenir ce fichier à jour

- **Un identifiant stable par chantier** (`SEC-1`, `UX-2`…). On ne le réutilise jamais, même
  après clôture : les commits et les specs y renvoient.
- **`Statut`** : `à faire` · `en cours` · `fait` (+ sha du commit) · `écarté` (+ la raison).
- **Un chantier terminé ne disparaît pas** : il passe à `fait` et sa ligne reste comme trace.
- Ce qui se transforme en **limitation assumée** part dans la section « Limitations connues »
  de [CLAUDE.md](../CLAUDE.md) ; ce qui se transforme en **décision** part dans la spec du
  chantier concerné. Ce fichier reste une liste de chantiers, pas un journal.
- `Effort` : S = moins d'une session, M = une à deux sessions, L = un chantier à part entière.

## 1. Sécurité & exposition

| ID | Chantier | Constat / risque | Effort | Prio | Statut |
|---|---|---|---|---|---|
| SEC-1 | Ne pas publier l'Actuator | Le port `9000` était publié sur toutes les interfaces, avec `show-details: always` et `metrics` : version PostgreSQL, URL JDBC, internes JVM | S | P0 | **fait** (publié sur `127.0.0.1` seulement ; un déploiement derrière proxy devra en plus ne pas router `/actuator`) |
| SEC-2 | Clé PRIM envoyée à un tiers | `GtfsStaticService.refresh` posait l'en-tête `apikey` sur **toutes** les requêtes GTFS, dont le miroir `eu.ftp.opendatasoft.com` de l'URL par défaut | S | P0 | **fait** (`requiresPrimKey`, clé envoyée au seul domaine PRIM) |
| SEC-3 | Rate limiting | Les 3 endpoints sont anonymes et sans quota. `/vehicles` recalcule ~705 positions par appel : un client qui boucle coûte du CPU linéairement | M | P1 si public | à faire |
| SEC-4 | En-têtes de sécurité + TLS | [nginx.conf](../frontend/nginx.conf) : ni CSP, ni `X-Frame-Options`, ni HSTS, ni `server_tokens off`, ni `X-Forwarded-For` vers le back, ni cache-control sur les assets hashés. Aucun scénario HTTPS | M | P1 si public | à faire |
| SEC-5 | Secrets hors du code | `mapidf/mapidf` était en dur dans `application.yml` et dans les composes | S | P1 | **fait** (`.env` seule source, zéro défaut dans le code, `spring.config.import` pour le CLI) |
| SEC-6 | Chaîne d'appro | Aucun scan de dépendances (Dependabot, `npm audit`, dependency-check), image backend en **root**, `COPY . .` avant résolution Maven (aucune couche de cache), pas de `HEALTHCHECK` | M | P2 | à faire |
| SEC-7 | Garde-fou de configuration | Le Binder des `@ConfigurationProperties` ignore les placeholders non résolus (il en garde le texte littéral), là où `Environment.getProperty` lève. Conséquence mesurée : sans clé PRIM, l'appli démarrait, PRIM répondait 401, le poller avalait l'échec et `/vehicles` servait zéro véhicule — « 0 trains en circulation » sans alerte | S | P1 | **fait** (`ConfigurationGuard`, `BeanFactoryPostProcessor` qui nomme les variables absentes) |
| SEC-8 | ~~Postgres local sans mot de passe~~ | **Réfuté.** Le démarrage réussissait parce que le `.env` était bel et bien importé : `--spring.config.import` en ligne de commande **s'ajoute** aux imports du YAML, il ne les remplace pas. Le mot de passe fourni était donc le vrai. Postgres n'a jamais rien accepté d'autre | — | — | **écarté** (mesure faussée) |
| SEC-9 | ~~PRIM sert le flux sans clé valide~~ | **Réfuté** le 2026-07-30 par appel direct : `estimated-timetable` répond **401** sans en-tête `apikey`, et 401 avec la clé littérale non résolue. Le `[RT] Poll réussi` observé venait de la même mesure faussée que SEC-8. Aucune conséquence sur LEG-2 | — | — | **écarté** (mesure faussée) |

## 2. Légal — porte d'entrée d'une mise en ligne

Ce ne sont pas des chantiers à planifier mais des **conditions** : elles bloquent la publication
publique, pas le développement local (cf. art. 5.6.c, l'usage interne n'est pas une utilisation
publique). Le détail est dans la section « Données, sources et licences » du
[README](../README.md).

| ID | Point | Constat | Effort | Prio | Statut |
|---|---|---|---|---|---|
| LEG-1 | Partage à l'identique (art. 5.5 / 5.6.d) | Le PostGIS peuplé par `GtfsStaticLoader` est une base dérivée ; `/network` ouvert au public peut déclencher l'obligation de republication sur transport.data.gouv.fr | M | P0 avant publication | à trancher |
| LEG-2 | CGU PRIM + quotas | Non vérifiées (le site renvoie 403 hors navigateur). Couvrent les quotas et l'éventuelle compensation financière | S | P0 avant publication | à trancher |
| LEG-3 | Marques | La Licence Mobilité exclut les marques déposées : indices de ligne, couleurs officielles, logos — tout ce que la carte affiche | S | P0 avant publication | à trancher |
| LEG-4 | Fond de carte sans SLA | `tiles.openfreemap.org` est gratuit et sans engagement : acceptable en perso, fragile dès qu'il y a du trafic. Auto-hébergement ou fournisseur payant | M | P2 | à faire |
| LEG-5 | RGPD | Rien à déclarer aujourd'hui (aucune donnée perso, aucun tracker). Devient un sujet **dès la géolocalisation** (cf. UX-5) ou tout analytics | S | P2 conditionnel | sans objet |

## 3. UX / UI

| ID | Chantier | Constat | Effort | Prio | Statut |
|---|---|---|---|---|---|
| UX-1 | États de chargement et d'erreur | `useNetwork` ne fetchait qu'une fois : au premier démarrage (109 Mo de GTFS), `/network` répond 200 vide → carte blanche définitive jusqu'à un rechargement manuel. Et un `/vehicles` en échec était avalé silencieusement | S | P1 | **fait** (bandeau `NetworkStatus` + retry 10 s tant que le réseau manque ; mention « rafraîchissement interrompu » dans le `LinePicker`). Limite assumée : le panneau station garde ses passages en silence, l'alerte globale du `LinePicker` couvrant la même panne |
| UX-2 | Adaptation mobile | Les 3 panneaux flottent à largeur fixe (260–300 px) et les 16 pastilles occupent le bas de l'écran. Déjà documenté dans les limitations | M | P1 | **fait** (feuille repliable unique à trois crans sous 720 px ; les panneaux ne se positionnent plus eux-mêmes — `Sheet` / `FloatingCard` ; `map.setPadding` par cran pour que les recentrages tombent au-dessus de la feuille ; cibles tactiles à 44 px via `--tap`). Vitest introduit au passage pour l'arithmétique des crans, avance sur QUA-3 |
| UX-3a | Signaux non expliqués (front seul) | Constat initial **erroné**, tiré d'une note de revue périmée : le badge « retardé » existait depuis `8d5300c`, les libellés d'état depuis `bd4d288`, et la légende des trains atténués est dans le `LinePicker`. Le vrai trou était `CANCELLED` : un passage supprimé s'affichait comme un départ normal | S | P1 | **fait** (badge rouge + heure barrée, `statusKind` partagé entre les deux panneaux) |
| UX-3b | « Service terminé » ≠ panne | Le poller s'arrête à 01h30 (`RealtimePoller.inServiceHours`), donc la nuit la carte se vide comme si le flux était tombé. Le front ne connaît pas les heures de service : demande un signal côté API (drapeau dans `/vehicles`), d'où sa séparation d'UX-3a | S | P2 | à faire |
| UX-4 | Accessibilité | Aucun accès clavier (tout passe par des clics carte), panneaux en `div` sans rôles ni gestion du focus, information portée par la seule couleur (13/3bis et 6/7bis identiques), styles inline sans thème sombre | M | P2 | à faire |
| UX-5 | Fonctions attendues absentes | Recherche de station, permalien / état dans l'URL (partager une ligne ou un train), « trains autour de moi », sens des tracés sur la carte, plus de 3 passages par direction | M | P2 | à faire |

## 4. Performance & architecture

| ID | Chantier | Constat | Effort | Prio | Statut |
|---|---|---|---|---|---|
| PERF-1 | Robustesse du refresh GTFS | `refresh` n'avait ni timeout de requête, ni contrôle du code HTTP, ni GET conditionnel : 125 Mo retéléchargés chaque jour même inchangés, et une réponse d'erreur partait au parseur ZIP | S | P1 | **fait** (`If-None-Match`/`If-Modified-Since` — 304 mesuré sur le miroir —, 304 traité, non-2xx levé, `timeout` posé). **Limite assumée** : avec `ofInputStream`, `HttpRequest.timeout` borne l'attente de la réponse, pas le transfert des 125 Mo ; une connexion **gelée en cours de corps** suspendrait toujours le refresh (il faudrait un chien de garde qui ferme le flux) |
| PERF-2 | Backoff sur 429 | `RealtimePoller.fetch` détecte bien le non-2xx mais réessaie au même rythme : sur dépassement de quota, on tape dans le mur toutes les 60 s | S | P2 | à faire |
| PERF-3 | Cache HTTP de `/vehicles` | Aucun `ETag`/`Cache-Control` (contrairement à `/network`). Un cache serveur de ~1 s absorberait N clients | S | P2 | à faire |
| PERF-4 | Interpolation côté client | Aujourd'hui 705 interpolations JTS + sérialisation **par client et par appel** (toutes les 4 s), alors que la source ne bouge qu'à 60 s. Envoyer le segment (`from`/`to` le long de la branche + horaires) une fois par minute et laisser le front interpoler sur une géométrie qu'il possède déjà : ~15× moins d'appels, coût serveur constant | L | P2 | à faire |
| PERF-5 | Push au lieu de poll | SSE/WebSocket aligné sur le poll de 60 s supprime le polling aveugle de `/vehicles` **et** de `/stations/{id}/departures` (rafraîchi toutes les 4 s par panneau ouvert) | M | P2 | à faire |
| PERF-6 | Instance unique implicite | Registry et snapshot vivent en mémoire, le poller est `@Scheduled` : **scaler à 2 instances double les appels PRIM** (quota) et désynchronise les cartes. Il faudrait un poller élu ou un snapshot externalisé | M | P2 si public | à faire |

## 5. Qualité & outillage

| ID | Chantier | Constat | Effort | Prio | Statut |
|---|---|---|---|---|---|
| QUA-1 | CI | Pas de `.github/` : toute la discipline `./mvnw verify` + `npm run build` est manuelle | S | — | **écarté** (2026-07-30, décision produit : pas maintenant) |
| QUA-2 | Métriques exploitables | Les jauges par ligne existent (`mapidf.rt.journeys`, `mapidf.position.*`) mais **aucun registre Prometheus** n'est dans le `pom.xml` : impossible d'alerter sur `journeys{line=X} == 0` ou sur `poll.failures`. Le garde-fou observable est aveugle | S | P1 | **fait** (registre Prometheus + `LineCoverageGuard` : WARN quand une ligne suivie reste 15 min à zéro alors que le réseau circule — réseau entier à zéro = panne de flux, donc silence). Le garde-fou n'attend plus qu'on pense à lire une métrique |
| QUA-3 | Outillage front | **Vitest est en place** depuis UX-2 (16 tests sur `sheetCrans` et `lineOrder`) — restent ESLint, Prettier, les autres fonctions pures (`formatEta`, `color`, `statusKind`, `severityStyle`, `badgeText`, `toggleLine`, culling de `VehicleLayer`) et un harnais de composants, dont UX-2 a montré le besoin : ses défauts de geste et de caméra ne se voyaient qu'à l'œil | M | P1 | **entamé** (Vitest + 2 modules testés) |
| QUA-4 | Seuil de couverture | Jacoco produit un rapport, sans règle `check` : la couverture peut chuter sans que `verify` rougisse | S | P2 | à faire |
| QUA-5 | Dépendances en retard | React 18, Vite 5, MapLibre 4, Node 20 dans l'image — des majeures existent pour les quatre | M | P2 | à faire |
| QUA-6 | Doublon de compose | Un `docker-compose.yml` à la racine (pile complète) **et** dans `backend/` (base seule, backend hors Docker). Les deux ont désormais un en-tête qui dit lequel sert à quoi ; reste à décider si un seul fichier suffirait | S | P3 | atténué |
| QUA-7 | OpenAPI | 3 endpoints publics documentés seulement en prose dans le README | S | P2 | à faire |

## 6. Évolutions produit

| ID | Piste | Pourquoi c'est la bonne direction | Effort | Prio | Statut |
|---|---|---|---|---|---|
| PROD-1 | Perturbations (`disruptions_bulk`) | Le manque le plus criant : on voit des trains, jamais « interruption entre X et Y ». SIRI `general-message` écarté (vide pour le métro, un paramètre par ligne) | M | P1 | **fait** — backend (poller 5 min, `DisruptionSnapshot` indexé ligne + arrêt, `GET /disruptions` filtré sur l'instant, 13 tests). **Front fait** : gravité sur les pastilles (couleur + glyphe), liste dépliable (pastille de ligne + badge plein + cause), et **anneau par gravité sur les stations non desservies** (`/disruptions` résout les quais en stations parentes). **Écarté** : tronçons en pointillés — le flux ne donne l'entre-deux-stations que dans du texte libre ; et INFORMATION n'est pas peinte sur la carte. Le message HTML du flux est **réduit en texte brut côté serveur** et servi en `detail` : il porte souvent la seule information utile (titre « Information - Autre », sens entier dans le message). La fiche station porte les perturbations de **ses quais** (`DisruptionRow` partagé avec le sélecteur), placées avant les passages. Les perturbations de ligne entière ne sont PAS répétées à chaque station : elles vivent dans le sélecteur, les redire noierait une correspondance à 5 lignes |
| PROD-2 | Étendre le périmètre : tram, puis RER/Transilien | L'archi est prête (`app.network.modes`), c'est le gain fonctionnel le moins cher. Le tram est à faible risque ; le RER apportera de vraies branches complexes et éprouvera `pickBranch` | M → L | P2 | à faire |
| PROD-3 | Calendrier de service (`calendar.txt`) | Débloque « service terminé » vs « panne » et une notion d'horaire théorique daté, aujourd'hui absente | M | P2 | à faire |
| PROD-4 | Fiabilité de placement | Ticket ouvert depuis le 2026-07-24 : les courses à un seul appel (~1/3 du flux) restent mal placées, signalées (`APPROXIMATE`) mais pas corrigées. Piste non temporelle : recouper deux polls consécutifs pour borner la progression réelle. **Jamais un seuil d'ETA** (décision produit ferme) | M | P2 | à faire |
| PROD-5 | Historisation des snapshots | Ouvre la ponctualité réelle, le replay d'une journée, les cartes de chaleur. C'est le passage de « visualisation » à « produit qui dit quelque chose » | L | P3 | à faire |

## Ordre recommandé

Arrêté le 2026-07-30. Critère : valeur visible rapportée à l'effort, les petits chantiers
groupés avant le gros. Les points **LEG** n'y figurent pas : ce ne sont pas des tâches mais la
porte d'un déploiement public, qui n'est pas d'actualité.

1. ~~**SEC-5**, **SEC-1**, **SEC-2**, **UX-1**, **UX-3a**, **SEC-7**, **PERF-1**, **PROD-1**,
   **QUA-2**, **UX-2**~~ — faits.
   **SEC-8** et **SEC-9** sont tombés en même temps : réfutés, ils n'ont jamais existé (cf. leurs
   lignes).
2. **QUA-3** (outillage front) — le suivant, et UX-2 a montré pourquoi : cinq de ses six défauts
   n'ont été trouvés qu'en relisant le code ou à l'œil sur un téléphone, faute de harnais. Vitest
   est déjà là ; restent ESLint, Prettier, les autres fonctions pures et de quoi tester un
   composant.

**Volontairement repoussés** : **SEC-8** (risque réel faible, base locale sur loopback),
**UX-3b** (demande un signal API), **PERF-4/5/6** (prématurés à un seul utilisateur), **QUA-5**
(montées de version majeures : du bruit tant que rien ne casse).
