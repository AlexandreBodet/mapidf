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
| SEC-4 | En-têtes de sécurité + TLS | [nginx.conf](../frontend/nginx.conf) : ni CSP, ni `X-Frame-Options`, ni HSTS, ni `server_tokens off`, ni `X-Forwarded-For` vers le back, ni cache-control sur les assets hashés. Aucun scénario HTTPS | M | P1 si public | **fait** — [spec](superpowers/specs/2026-07-31-sec-4-entetes-securite-tls-design.md). CSP stricte (`script-src 'self'`, sans `unsafe-eval`), en-têtes de sécurité inclus par chaque `location` **et** au niveau `server` (les erreurs émises avant tout choix de `location` sortaient nues — mesuré sur un 400), cache un an sur les assets hachés et `no-cache` sur `index.html`, gzip, `server_tokens off`, `X-Forwarded-Proto` préservé par un `map` (un `proxy_set_header $scheme` écrasait la valeur du terminateur), et `scripts/check-headers.sh` qui échoue si un en-tête disparaît. CSP éprouvée **en navigateur sur deux moteurs** (Chrome et Firefox : raster, glyphes, sprites, worker blob, images `data:`/`blob:`), pas seulement raisonnée. Absorbe la part « Dockerfile » de SEC-6 : front en uid 101 (`nginx-unprivileged`), backend en uid 10001, `HEALTHCHECK` des deux côtés, résolution Maven en couche cachée. **Reste à SEC-6** : scan de dépendances. **TLS non terminé par la pile** : le scénario est documenté dans le README, la décision revient à l'hébergeur |
| SEC-5 | Secrets hors du code | `mapidf/mapidf` était en dur dans `application.yml` et dans les composes | S | P1 | **fait** (`.env` seule source, zéro défaut dans le code, `spring.config.import` pour le CLI) |
| SEC-6 | Chaîne d'appro | Aucun scan **automatique** de dépendances (Dependabot, dependency-check). Le durcissement des images (non-root, `HEALTHCHECK`, cache de couche Maven) a été absorbé par SEC-4. Audit manuel du 2026-08-10 : `npm audit` remonte 7 vulnérabilités (4 moderate, 2 high, 1 critical) **toutes en devDependencies** — la prod (`maplibre-gl`, `react`, `react-dom`) n'est pas touchée, et vérifié : aucun de ces paquets ne laisse de trace dans le bundle livré, donc **rien n'atteint le navigateur**. La critique (vitest) exige le serveur *UI* de Vitest, jamais lancé ici (`vitest run` seulement). La seule qui morde en pratique : `vite`/`esbuild`, dont le serveur de dev est joignable par n'importe quelle page ouverte à côté pendant un `npm run dev`. Côté backend, rien n'est scanné du tout | M | P2 | à faire |
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
| UX-3b | « Service terminé » ≠ panne | Constat initial **incomplet** : la nuit, la carte ne se vidait pas — le snapshot survivait au dernier poll et `PositionEngine` place au dernier arrêt connu quand tous les appels sont passés, donc ~705 courses restaient figées à leur terminus, annoncées « en circulation ». Et `/vehicles` datait sa réponse de l'instant du **calcul**, tamponnant une heure fraîche sur une donnée de la veille (art. 5.7) | S | P2 | **fait** (fenêtre de poll élargie à 03h00 pour ne pas effacer la queue de service, snapshot oublié hors service, `asOf` = date de la donnée, drapeau `inService` dans `/vehicles`, « Service terminé — reprise au premier métro » côté front ; `LineCoverageGuard` reçoit sa propre fenêtre 06h30–00h30, sans quoi la queue de service déclencherait un WARN par ligne éteinte) |
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
| QUA-3 | Outillage front | **Fait.** Vitest est arrivé avec UX-2 ; QUA-3 y a ajouté un harnais de composants (jsdom + Testing Library, environnement déclaré par fichier pour que les tests de fonctions pures restent en Node) et les fonctions pures qui restaient. La liste d'origine de ce chantier était partiellement fausse, cf. § 2 de la spec : `color` n'existait pas, `badgeText` était privée, `toggleLine` était une fermeture inline | M | P1 | **fait** — [spec](superpowers/specs/2026-08-10-qua-3-outillage-front-design.md). Trois régressions réellement livrées ont désormais un test qui rougit si on remet le bug (préfixe de ligne à République, « Service terminé », format `3 min`), et le défaut clavier-après-glissement d'UX-2 aussi. **Hors périmètre assumé** : `App.tsx` et la caméra (faux MapLibre complet pour un défaut déjà évité par un contrôle conditionnel dans le code, pas par un test — `if (map.getPadding().bottom !== bottom)` à `App.tsx:215`), le culling de `VehicleLayer`. **ESLint est parti avec QUA-5** et **Prettier avec QUA-8** — deux reformatages massifs coup sur coup se marcheraient dessus |
| QUA-4 | Seuil de couverture | Jacoco produit un rapport, sans règle `check` : la couverture peut chuter sans que `verify` rougisse | S | P2 | à faire |
| QUA-5 | Dépendances en retard | React 18, Vite 5, MapLibre 4, Node 20 dans l'image — des majeures existent pour les quatre. **A gagné un argument le 2026-08-10** : les 7 vulnérabilités relevées par `npm audit` (cf. SEC-6) exigent toutes une montée de majeure — `npm audit fix` seul ne corrige **rien**. Le motif de report (« du bruit tant que rien ne casse ») ne couvre donc plus tout. À faire **après QUA-3**, qui fournira le harnais permettant de constater qu'une majeure n'a rien cassé | M | P2 | à faire |
| QUA-6 | Doublon de compose | Un `docker-compose.yml` à la racine (pile complète) **et** dans `backend/` (base seule, backend hors Docker). Les deux ont désormais un en-tête qui dit lequel sert à quoi ; reste à décider si un seul fichier suffirait | S | P3 | atténué |
| QUA-7 | OpenAPI | 3 endpoints publics documentés seulement en prose dans le README | S | P2 | à faire |
| QUA-8 | Sortir du style inline | Un attribut `style` ne peut exprimer ni `:hover`, ni `:focus-visible`, ni `@media`, ni `prefers-color-scheme` : **UX-4 est bloquée par là** (états de focus pour le clavier, thème sombre). Permettrait aussi `style-src-attr 'none'`, mais c'est le gain de second ordre — l'attribut n'ouvre quelque chose qu'à qui a déjà une injection HTML (cf. spec SEC-4 § 9). Le projet a déjà un `index.css` depuis UX-2 : la porte est ouverte. **À ne pas attaquer avant QUA-3** : quinze composants convertis à la main sans harnais de test, c'est le terrain des régressions muettes qu'UX-2 a documentées. **Angles non couverts par QUA-3, à reprendre à cette occasion** : les usages de `badgeText` et de `leading` dans le rendu de `DisruptionRow` (seules les fonctions ont des tests unitaires, rien ne prouve que le composant les appelle), le titre affiché dans sa branche « bouton », `onSelectLine` de `StopPanel`, la fraîcheur `asOf` sur la poignée de `Sheet` (jamais montée en test — `renderSheet` passe toujours `asOf={null}` — alors que l'art. 5.7 l'exige sous 720 px), `onPointerCancel`, et `MOVE_THRESHOLD`. **Et un piège de harnais à traiter ici même** : le helper `isHidden` de `Sheet.test.tsx` ne lit que le `style.display` **inline**, donc il devient aveugle dès que le repli s'exprime autrement (mesuré : masquer l'alerte par l'attribut `hidden` laisse les 12 tests verts). Sortir du style inline sans le faire passer à `getComputedStyle` transformerait ces tests en faux verts | M → L | P2 | à faire |

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
   **QUA-2**, **UX-2**, **UX-3b**, **SEC-4**, **QUA-3**~~ — faits.
   **SEC-8** et **SEC-9** sont tombés en même temps : réfutés, ils n'ont jamais existé (cf. leurs
   lignes).
2. **QUA-8** (sortir du style inline) puis **UX-4** — QUA-3 a livré le harnais qui manquait pour
   convertir quinze composants sans régression muette. **QUA-5** peut se glisser avant QUA-8 : ses
   montées de majeure éteignent les 7 vulnérabilités de l'outillage (cf. SEC-6) et amènent ESLint.
3. **SEC-3** + **PERF-3** ensemble, le jour où une mise en ligne se précise : un cache serveur de
   ~1 s sur `/vehicles` (effort S) retire l'essentiel de la charge qu'un quota devrait ensuite
   borner. SEC-4 a fermé ce qui pouvait l'être sans hébergeur ; le reste attend une décision de
   déploiement, **SEC-6** (scan de dépendances) compris.

**Volontairement repoussés** : **SEC-8** (risque réel faible, base locale sur loopback),
**PERF-4/5/6** (prématurés à un seul utilisateur), **QUA-5** — mais plus pour la même raison :
les montées de majeure sont désormais le seul remède aux 7 vulnérabilités de l'outillage front
(cf. SEC-6), donc elles passent de « bruit » à « dette datée », à traiter juste après QUA-3.
