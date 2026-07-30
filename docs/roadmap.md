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
| SEC-1 | Ne pas publier l'Actuator | [docker-compose.yml](../docker-compose.yml) publie le port `9000`, avec `show-details: always` et `metrics` exposés ([application.yml](../backend/src/main/resources/application.yml)). Fuite de version PostgreSQL, URL JDBC, internes JVM | S | P0 avant déploiement | à faire |
| SEC-2 | Clé PRIM envoyée à un tiers | `GtfsStaticService.refresh` pose l'en-tête `apikey` sur **toutes** les requêtes GTFS — or l'URL par défaut est le miroir `eu.ftp.opendatasoft.com`, pas PRIM. La clé part chez OpenDataSoft sans aucune utilité | S | P0 | à faire |
| SEC-3 | Rate limiting | Les 3 endpoints sont anonymes et sans quota. `/vehicles` recalcule ~705 positions par appel : un client qui boucle coûte du CPU linéairement | M | P1 si public | à faire |
| SEC-4 | En-têtes de sécurité + TLS | [nginx.conf](../frontend/nginx.conf) : ni CSP, ni `X-Frame-Options`, ni HSTS, ni `server_tokens off`, ni `X-Forwarded-For` vers le back, ni cache-control sur les assets hashés. Aucun scénario HTTPS | M | P1 si public | à faire |
| SEC-5 | Secrets hors du code | `mapidf/mapidf` en dur dans `application.yml` et dans le compose. Cible : tous les secrets dans `.env` (gitignoré) et **plus aucune valeur par défaut** dans `application.yml`, pour que le démarrage échoue vite au lieu de chercher un `localhost:5432` inexistant. Les deux chemins de lancement sont couverts : `docker compose` lit `.env` nativement, IntelliJ l'injecte déjà (config de run). **Reste** : `./mvnw spring-boot:run` en CLI, documenté dans le README, ne lit pas `.env` → soit `spring.config.import: optional:file:../.env[.properties]`, soit documenter `set -a; source ../.env`. À traiter aussi le défaut vide `${PRIM_API_KEY:}`, qui fait démarrer l'appli **sans temps réel et sans le dire** | S | P1 | à faire |
| SEC-6 | Chaîne d'appro | Aucun scan de dépendances (Dependabot, `npm audit`, dependency-check), image backend en **root**, `COPY . .` avant résolution Maven (aucune couche de cache), pas de `HEALTHCHECK` | M | P2 | à faire |

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
| UX-1 | États de chargement et d'erreur | `useNetwork` fetch **une seule fois** et ne réessaie jamais. Au premier démarrage (109 Mo de GTFS), `/network` répond 200 vide → **carte blanche définitive** jusqu'à un rechargement manuel. Idem, un `/vehicles` en échec est avalé silencieusement | S | P1 | à faire |
| UX-2 | Adaptation mobile | Les 3 panneaux flottent à largeur fixe (260–300 px) et les 16 pastilles occupent le bas de l'écran. Déjà documenté dans les limitations | M | P1 | à faire |
| UX-3 | Signaux non expliqués | L'opacité réduite (`confidence: APPROXIMATE`) n'a aucune légende. « Service terminé » (poller arrêté à 01h30) ne se distingue pas d'une panne. `Passage.status` (`DELAYED`) est transmis mais jamais affiché dans `StopPanel` | S | P1 | à faire |
| UX-4 | Accessibilité | Aucun accès clavier (tout passe par des clics carte), panneaux en `div` sans rôles ni gestion du focus, information portée par la seule couleur (13/3bis et 6/7bis identiques), styles inline sans thème sombre | M | P2 | à faire |
| UX-5 | Fonctions attendues absentes | Recherche de station, permalien / état dans l'URL (partager une ligne ou un train), « trains autour de moi », sens des tracés sur la carte, plus de 3 passages par direction | M | P2 | à faire |

## 4. Performance & architecture

| ID | Chantier | Constat | Effort | Prio | Statut |
|---|---|---|---|---|---|
| PERF-1 | Robustesse du refresh GTFS | `GtfsStaticService.refresh` n'a **pas de timeout de requête** (seulement un `connectTimeout`) et aucun GET conditionnel : 109 Mo retéléchargés chaque jour même inchangés, et une connexion pendue laisse le refresh suspendu | S | P1 | à faire |
| PERF-2 | Backoff sur 429 | `RealtimePoller.fetch` détecte bien le non-2xx mais réessaie au même rythme : sur dépassement de quota, on tape dans le mur toutes les 60 s | S | P2 | à faire |
| PERF-3 | Cache HTTP de `/vehicles` | Aucun `ETag`/`Cache-Control` (contrairement à `/network`). Un cache serveur de ~1 s absorberait N clients | S | P2 | à faire |
| PERF-4 | Interpolation côté client | Aujourd'hui 705 interpolations JTS + sérialisation **par client et par appel** (toutes les 4 s), alors que la source ne bouge qu'à 60 s. Envoyer le segment (`from`/`to` le long de la branche + horaires) une fois par minute et laisser le front interpoler sur une géométrie qu'il possède déjà : ~15× moins d'appels, coût serveur constant | L | P2 | à faire |
| PERF-5 | Push au lieu de poll | SSE/WebSocket aligné sur le poll de 60 s supprime le polling aveugle de `/vehicles` **et** de `/stations/{id}/departures` (rafraîchi toutes les 4 s par panneau ouvert) | M | P2 | à faire |
| PERF-6 | Instance unique implicite | Registry et snapshot vivent en mémoire, le poller est `@Scheduled` : **scaler à 2 instances double les appels PRIM** (quota) et désynchronise les cartes. Il faudrait un poller élu ou un snapshot externalisé | M | P2 si public | à faire |

## 5. Qualité & outillage

| ID | Chantier | Constat | Effort | Prio | Statut |
|---|---|---|---|---|---|
| QUA-1 | CI | Pas de `.github/` : toute la discipline `./mvnw verify` + `npm run build` est manuelle | S | — | **écarté** (2026-07-30, décision produit : pas maintenant) |
| QUA-2 | Métriques exploitables | Les jauges par ligne existent (`mapidf.rt.journeys`, `mapidf.position.*`) mais **aucun registre Prometheus** n'est dans le `pom.xml` : impossible d'alerter sur `journeys{line=X} == 0` ou sur `poll.failures`. Le garde-fou observable est aveugle | S | P1 | à faire |
| QUA-3 | Outillage front | Ni ESLint, ni Prettier, ni test unitaire — alors que `formatEta`, `color`, la logique de `toggleLine` et le culling de `VehicleLayer` sont testables et déjà subtils | M | P1 | à faire |
| QUA-4 | Seuil de couverture | Jacoco produit un rapport, sans règle `check` : la couverture peut chuter sans que `verify` rougisse | S | P2 | à faire |
| QUA-5 | Dépendances en retard | React 18, Vite 5, MapLibre 4, Node 20 dans l'image — des majeures existent pour les quatre | M | P2 | à faire |
| QUA-6 | Doublon de compose | Un `docker-compose.yml` à la racine **et** dans `backend/` : source de confusion | S | P2 | à faire |
| QUA-7 | OpenAPI | 3 endpoints publics documentés seulement en prose dans le README | S | P2 | à faire |

## 6. Évolutions produit

| ID | Piste | Pourquoi c'est la bonne direction | Effort | Prio | Statut |
|---|---|---|---|---|---|
| PROD-1 | Perturbations (SIRI *general-message* / *line-reports*) | Le manque le plus criant : on voit des trains, jamais « interruption entre X et Y ». C'est aussi ce qui donne du sens au `DepartureStatus: DELAYED` déjà capté (cf. UX-3) | M | P1 | à faire |
| PROD-2 | Étendre le périmètre : tram, puis RER/Transilien | L'archi est prête (`app.network.modes`), c'est le gain fonctionnel le moins cher. Le tram est à faible risque ; le RER apportera de vraies branches complexes et éprouvera `pickBranch` | M → L | P2 | à faire |
| PROD-3 | Calendrier de service (`calendar.txt`) | Débloque « service terminé » vs « panne » et une notion d'horaire théorique daté, aujourd'hui absente | M | P2 | à faire |
| PROD-4 | Fiabilité de placement | Ticket ouvert depuis le 2026-07-24 : les courses à un seul appel (~1/3 du flux) restent mal placées, signalées (`APPROXIMATE`) mais pas corrigées. Piste non temporelle : recouper deux polls consécutifs pour borner la progression réelle. **Jamais un seuil d'ETA** (décision produit ferme) | M | P2 | à faire |
| PROD-5 | Historisation des snapshots | Ouvre la ponctualité réelle, le replay d'une journée, les cartes de chaleur. C'est le passage de « visualisation » à « produit qui dit quelque chose » | L | P3 | à faire |

## Ordre recommandé

1. **SEC-5** (secrets dans `.env`, plus de défaut `mapidf`), **SEC-1** et **SEC-2** — trois
   correctifs courts qui referment des fuites bêtes.
2. **UX-1** — aujourd'hui, un premier démarrage donne un écran blanc silencieux.
3. **QUA-2** — sans registre Prometheus, le garde-fou par ligne ne sert à personne.
4. **PROD-1 + UX-3** — le plus gros gain perçu à effort moyen, et cohérent avec la décision
   « on veut voir les trains en perturbation ».
5. Puis **UX-2** (mobile), **PROD-2** (tram), et enfin **PERF-4** (interpolation côté client).
