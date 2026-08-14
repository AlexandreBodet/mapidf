# SEC-3 — Quota par IP sur les endpoints publics

*Document de conception — 2026-08-13*

Chantier [SEC-3](../../roadmap.md), la seconde moitié du couple que PERF-3 avait entamé seul.
PERF-3 a rendu le coût CPU des trois endpoints chauds constant par seconde ; il reste que rien ne
borne le **trafic** lui-même — connexions, bande passante, et le seul endpoint que PERF-3 a
délibérément laissé de côté.

**Deux écarts avec la fiche de la roadmap**, tous deux tranchés au cadrage :

- La fiche plaçait le quota dans nginx (« même fichier et même préoccupation de bordure que
  `limit_req` »). Le quota porteur passe **dans le backend** ; nginx devient un second rideau
  (§ 2.1).
- La fiche faisait hériter SEC-3 du **micro-cache nginx** que PERF-3 avait reporté. Il **sort du
  périmètre** (§ 7).

## 1. Objectif et critères de réussite

Empêcher qu'un client emballé ou un aspirateur consomme le service sans limite, dans **toutes** les
topologies de déploiement envisagées (compose sur VM, Kubernetes managé, conteneurs serverless),
sans jamais gêner un usage humain — y compris derrière un NAT partagé.

Est réussi si, tout appliqué :

1. Une boucle sans délai depuis une IP publique reçoit des **429** en moins d'une seconde, avec un
   `Retry-After` exploitable et le corps d'erreur déjà servi partout ailleurs.
2. Le quota couvre **les quatre** endpoints, `/network` compris (§ 2.2).
3. La clé du quota est **la vraie IP cliente** derrière un proxy, et n'est pas forgeable par un
   client public — seul un pair déjà dans le périmètre privé le peut (§ 2.3).
4. `./mvnw verify` est vert, avec plus de tests qu'avant. Référence mesurée au point de départ de
   la branche (`af8804a`) : **114 exécutions unitaires + 53 IT**, soit 167 au total. Le « 167
   unitaires » d'une note antérieure était ce total mal étiqueté.
5. `mapidf.ratelimit.rejected` et un WARN borné permettent de constater qu'un rejet a eu lieu sans
   qu'on ait pensé à lire une métrique (§ 6).
6. Aucun changement côté front (§ 7).

## 2. Ce qui décide de la conception

### 2.1 nginx n'est pas sur le chemin dans deux topologies sur trois

Les déploiements visés sont Scaleway ou un Kubernetes. Dans les deux cas,
`frontend/nginx.conf` cesse d'être garanti sur le chemin de `/api` :

- **Kubernetes.** Un Ingress route en général `/api/*` vers le Service backend directement, notre
  nginx redevenant un serveur de fichiers statiques. Et quand il reste proxy, le rate limiting d'un
  ingress-nginx se configure par **annotations sur la ressource Ingress** — hors de ce dépôt, donc
  hors de ce que `./mvnw verify` ou `scripts/check-headers.sh` peuvent constater.
- **Conteneurs serverless.** Il n'y a pas de nginx devant le backend du tout : chaque conteneur a
  sa propre URL publique.

Le backend est le seul point qui reste sur le chemin dans les trois cas. Un quota écrit dans
`nginx.conf` **disparaîtrait au déploiement sans que rien ne le signale** — exactement le mode de
panne que ce chantier existe pour éviter.

nginx garde tout de même son `limit_req` (§ 5) : dans la pile compose, il protège ses propres
workers d'une inondation que le backend rejetterait certes, mais après l'avoir acceptée.

### 2.2 `/network` est aujourd'hui l'endpoint le plus cher par appel

PERF-3 a caché `/vehicles`, `/disruptions` et `/stations/{id}/departures`. Il a écarté `/network` à
raison : un client l'appelle une fois. Mais `NetworkController` resérialise **8 110 points de tracé
plus toutes les stations à chaque appel**, sans aucun cache serveur, et sa seule protection —
`Cache-Control: max-age=600` — est **côté client**, donc inopérante contre une boucle qui ne
consulte aucun cache.

Le quota est donc **global sur `/api/**`, en un seul budget**, pas par endpoint. Une répartition par
endpoint donnerait à `/network` un budget propre, alors que ce qu'on veut borner est la somme.

### 2.3 `getRemoteAddr()` ne donne pas l'IP cliente, et `X-Forwarded-For` est ce que le client veut

Derrière nginx, un Ingress ou un LB, toutes les requêtes arrivent de l'IP du proxy. Un quota « par
IP » posé naïvement deviendrait un **quota global** qui coupe tout le monde dès le premier abuseur.

À l'inverse, lire `X-Forwarded-For` sans discernement rend le quota contournable : l'en-tête est
choisi par le client, il suffit d'en changer un chiffre à chaque requête.

Le réglage retenu est `server.forward-headers-strategy: native`, **et pas `framework`**. La
différence porte précisément là-dessus :

- `framework` installe le `ForwardedHeaderFilter` de Spring, qui **n'a pas de notion de proxy de
  confiance** — il croit l'en-tête.
- `native` installe le `RemoteIpValve` de Tomcat, configuré par `server.tomcat.remoteip.*`. Son
  défaut `internal-proxies`, **vérifié le 2026-08-13 dans `spring-boot-tomcat-4.1.0.jar`**, vaut
  `192.168.0.0/16, 172.16.0.0/12, 169.254.0.0/16, fc00::/7, 10.0.0.0/8, 100.64.0.0/10,
  127.0.0.0/8, fe80::/10, ::1/128`.

Autrement dit : `X-Forwarded-For` n'est cru que s'il vient d'une adresse privée. Cela couvre nginx
en compose (réseau bridge Docker en 172.16/12), un Ingress dans un cluster et un LB Scaleway ; et
un client public tapant le port du backend en direct ne peut rien forger, son XFF étant ignoré au
profit de son adresse de socket.

**Angle mort accepté** : le `RemoteIpValve` retient l'entrée la plus à gauche de X-Forwarded-For
quand la chaîne entière est interne. La condition n'est donc pas l'absence de proxy devant — c'est
au contraire le proxy qui ajoute l'adresse du pair à la chaîne qui la rend entièrement interne, et
donc forgeable. Il faut que ce pair soit lui-même dans une plage interne : `100.64.0.0/10` (CGNAT)
en fait partie, tout comme `192.168/16`, `172.16/12` ou `10/8` — un pair du LAN, un conteneur
voisin du bridge Compose, un pod du cluster. Le périmètre d'exposition est donc déjà le réseau
privé, pas Internet : un client public termine toujours la chaîne et reste compté. Restreindre la
liste échangerait ce trou — déjà circonscrit à qui est dans le périmètre privé — contre une
configuration à tenir à jour dans trois déploiements.

### 2.4 L'IP est une clé imparfaite, et le quota doit en tenir compte

Derrière le NAT d'un opérateur mobile, d'un campus ou d'une entreprise, des centaines ou des
milliers d'usagers partagent une adresse. Un quota assez serré pour être « juste » entre usagers
couperait ces gens-là, et le symptôme côté navigateur serait le bandeau d'erreur générique d'UX-1,
pas un message compréhensible.

D'où la règle qui gouverne le chiffre : **ce quota arrête une boucle emballée ou un aspirateur, il
n'arbitre pas entre usagers.** Ce que consomme un client légitime, lu dans le code du front :

| Appel | Cadence | Par minute |
|---|---|---|
| `/vehicles` | `VEHICLE_POLL_MS = 4000` | 15 |
| `/stations/{id}/departures` | 4 s, fiche ouverte | 15 |
| `/disruptions` | `POLL_MS = 60_000` | 1 |
| `/network` | une fois au chargement (retry 10 s tant qu'il échoue) | ~1 |

Soit **~31 requêtes par minute et par onglet en pointe**. Le budget retenu est **600 par minute**,
soit une vingtaine d'onglets sur une même adresse — jamais atteint par un humain, y compris derrière
un NAT moyen, tandis qu'une boucle sans délai le franchit en une fraction de seconde. Un aspirateur
poli à 10 req/s passerait : c'est assumé, PERF-3 ayant rendu ce trafic-là peu coûteux.

### 2.5 Un filtre ne peut pas réutiliser le format d'erreur du projet

Une exception levée dans un `jakarta.servlet.Filter` se produit **hors** du `DispatcherServlet` :
l'`@RestControllerAdvice` ne la voit pas. Un filtre devrait donc réécrire à la main le statut, le
`Content-Type` et la sérialisation d'`ErrorResponse` — une seconde implémentation du format
d'erreur, qui divergerait de la première le jour où l'une des deux change.

Un `HandlerInterceptor` s'exécute **dans** le `DispatcherServlet`. Il lève une `ApiException` et
l'`ApiExceptionHandler` existant produit la réponse, au format déjà servi par
`STATION_NOT_FOUND`. C'est ce qui décide entre les deux.

**Conséquence à connaître, établie par test** (`RateLimitIT#compteAussiUnCheminNonMappe`) —
l'inverse de ce qu'on supposait ici au cadrage : un chemin **non mappé** sous `/api/` EST compté
par le quota. `spring.web.resources.add-mappings` vaut `true` par défaut, donc Boot enregistre un
`ResourceHttpRequestHandler` sur `/**`, et Spring MVC lui applique **les mêmes interceptors**
qu'aux contrôleurs — `RateLimitInterceptor` s'exécute donc aussi sur ces chemins. Sans
conséquence pour ce chantier : un chemin qui n'existe pas ne coûte de toute façon presque rien à
traiter. Le même test a révélé, hors périmètre, que ce chemin répond **500** au lieu d'un 404
(`ApiExceptionHandler` ne traite pas `NoResourceFoundException`) — ouvert en roadmap sous QUA-14.

## 3. Architecture retenue

### `RateLimiter`, dans `controllers/support`

Placé à côté de `ResponseCache` et calqué sur lui : même paquet, même forme, mêmes leçons.

- `ConcurrentHashMap<String, Entry>`, la clé étant l'IP cliente.
- La fenêtre est `clock.instant().truncatedTo(ChronoUnit.MINUTES)` — même idiome de troncature que
  `ResponseCache`, qui tronque à la seconde. Les fenêtres sont donc alignées sur la minute pour
  toutes les IP à la fois ; sans conséquence pour un limiteur d'abus.
- **Balayage des entrées d'une fenêtre périmée à chaque écriture.** C'est le défaut que la revue
  finale de PERF-3 a trouvé dans `ResponseCache` (des entrées mortes retenant leurs instantanés
  source). Ici l'entrée est minuscule, mais la map est indexée par une clé **que l'appelant
  choisit** : sans balayage, elle croît avec le nombre d'IP vues et ne décroît jamais. Le coût est
  O(nombre de clés) sur un franchissement de fenêtre.
- Le `Clock` injecté vient du bean `ClockConfiguration` posé par PERF-3 : la fenêtre est testable
  sans attendre une minute.

Le compteur est incrémenté puis comparé au budget : **la 600ᵉ requête d'une fenêtre passe, la
601ᵉ est refusée.** Comme pour `ResponseCache`, deux requêtes concurrentes peuvent franchir le
seuil ensemble — la précision au franchissement exact n'a aucune valeur ici.

**Prix assumé de la fenêtre fixe** : à cheval sur deux fenêtres, un client peut passer 1 200
requêtes en deux secondes. Un seau à jetons l'éviterait, au prix d'une arithmétique de recharge à
tester ; la fenêtre fixe suffit à ce que ce quota fait.

### `RateLimitInterceptor`

Enregistré sur `/**` par un `WebMvcConfigurer`, ce qui — le context-path valant `/api` — couvre les
quatre endpoints.

Il n'est **pas** posé sur l'Actuator : celui-ci vit sur le port 9100, dans un contexte enfant
distinct, que ce `WebMvcConfigurer` n'atteint pas. Et ce port n'est publié que sur la loopback
(compose racine).

**Loopback exempté.** `127.0.0.1` / `::1` après résolution XFF désigne la machine elle-même, jamais
un client public dans les trois topologies visées. L'exemption a un second effet, qui n'est pas un
effet de bord mais une raison : les IT de contrôleur passent par MockMvc, donc par
`remoteAddr = 127.0.0.1`, dans un contexte Spring **mis en cache entre classes de test** — ils
partageraient un même compteur, et la suite finirait par rougir à force de grossir. L'exemption les
laisse tels quels, et l'IT dédié (§ 8) pose une IP publique pour exercer le limiteur.

### Configuration

```yaml
app:
  ratelimit:
    requests-per-minute: 600
```

Un `@ConfigurationProperties` dans `configurations/properties/`, comme `NetworkProperties` et
`PrimProperties`.

**Valeur littérale, pas de variable `.env`.** L'en-tête de `.env.example` énonce que rien de ce
qu'il liste n'a de défaut dans le code — cette règle vise les **secrets et les coordonnées
d'infrastructure** (clé PRIM, base). Un budget de requêtes est un réglage fonctionnel, du même
genre que `app.prim.poll-interval: PT60S` ou `app.network.modes: [METRO]`, qui vivent en littéral
dans l'`application.yml`. Un déployeur qui veut le changer sans reconstruire dispose de la liaison
relâchée de Spring (`APP_RATELIMIT_REQUESTSPERMINUTE`) ; l'inscrire dans `.env.example` en ferait
une variable **obligatoire** — `ConfigurationGuard` (SEC-7) refuserait le démarrage sans elle —
pour un réglage que personne n'a besoin de renseigner.

La **fenêtre reste figée à une minute dans le code** : la rendre configurable n'a aucun usage réel,
alors que le budget en a deux — le régler par environnement, et le mettre à 3 dans l'IT dédié pour
ne pas émettre 601 requêtes.

## 4. Réponse à un rejet

- **429 Too Many Requests**, via `new ApiException(HttpStatus.TOO_MANY_REQUESTS,
  ErrorCode.TOO_MANY_REQUESTS)` — une valeur d'énumération à ajouter à `ErrorCode`, description
  `"Too many requests"`.
- Corps : l'`ErrorResponse` standard (`timestamp`, `status`, `errorCode`, `path`), produit par
  l'`ApiExceptionHandler` existant, sans une ligne de sérialisation nouvelle.
- En-tête **`Retry-After`** : les secondes restant jusqu'à la fin de la fenêtre courante, posé par
  l'interceptor sur la réponse **avant** de lever. L'`ApiExceptionHandler` n'appelle que
  `setStatus` et `setContentType`, donc l'en-tête survit — la réponse n'est pas encore validée.

Le `log.debug` de l'`ApiExceptionHandler` couvre déjà les erreurs 4xx ; le WARN borné du § 6 s'y
ajoute, il ne le remplace pas.

## 5. Second rideau nginx, et fermeture du port

Dans `location /api/` de `frontend/nginx.conf` — et **là seulement** : les fichiers statiques sont
peu coûteux et déjà cachés un an, les limiter n'apporterait rien et gênerait un rechargement forcé.

```nginx
# En tête de fichier, à côté du `map` : limit_req_zone n'est valide qu'au niveau `http`, et le
# fichier est inclus dans conf.d/, donc dans le bloc http.
limit_req_zone $binary_remote_addr zone=api:10m rate=10r/s;

# Dans location /api/ uniquement :
limit_req zone=api burst=60 nodelay;
limit_req_status 429;
```

`10 r/s` est le même ordre de grandeur que les 600/min du backend, mais **l'algorithme diffère** —
seau percé contre fenêtre fixe. Les deux ne déclencheront donc pas au même instant, et ce n'est pas
un défaut à corriger : le backend est la référence (il est le seul présent dans les trois
topologies), nginx protège ses propres workers dans la pile compose. Une zone de 10 Mo tient de
l'ordre de 160 000 adresses.

`limit_req_status 429` est nécessaire : nginx répond **503** par défaut, ce qui dirait « le service
est en panne » au lieu de « tu vas trop vite ».

**Fermeture du port 8100.** Le compose racine publie aujourd'hui le backend sur toutes les
interfaces (`ports: ["${SERVER_PORT:-8100}:8100", …]`), alors que l'Actuator juste en dessous est
délibérément restreint à `127.0.0.1`. Il passe sur la loopback lui aussi : une ligne, et le
contournement du rideau nginx disparaît de la pile compose. Le port reste joignable depuis la
machine, donc le développement local n'en souffre pas.

## 6. Observabilité

- Compteur **`mapidf.ratelimit.rejected`**, **sans tag d'IP** : la cardinalité d'un tag choisi par
  l'attaquant est un vecteur à elle seule. Exposé par `/actuator/prometheus` comme le reste.
- **Un WARN par IP et par fenêtre**, émis au franchissement exact du seuil (le compteur vaut
  `budget + 1`), donc borné par construction : un abuseur ne peut pas inonder les journaux. Même
  intention que `LineCoverageGuard` — une mesure ne sert à rien tant qu'il faut penser à la lire.
  Le WARN nomme l'IP ; c'est une donnée personnelle au sens du RGPD, sans conséquence tant que rien
  n'est conservé au-delà des journaux applicatifs (cf. LEG-5, aujourd'hui sans objet).

## 7. Hors périmètre, et pourquoi

- **Le micro-cache nginx.** PERF-3 le lui avait légué. Il en sort : le gain restant mesuré par
  PERF-3 est de **1,65 ms par hit**, soit à 40 clients (10 req/s) environ **1,6 % d'un cœur**, plus
  219 ko × 10/s sur le réseau interne ; et il souffre du défaut du § 2.1 — il n'existerait que dans
  la pile compose. Le conflit avec le `Cache-Control: no-store` de PERF-3 n'est **pas** ce qui
  bloque : `proxy_ignore_headers Cache-Control` le résout proprement (nginx cache une seconde, le
  client reste en `no-store`, aucune péremption chez un intermédiaire). C'est le rapport entre le
  gain et les pièces mobiles qui décide. À rouvrir si une mesure en production le réclame.
- **La multiplication par le nombre de réplicas.** Un compteur en mémoire donne, à N pods, un quota
  réel de N × 600. C'est PERF-6 (« instance unique implicite ») qui porte ce sujet, avec le poller
  et le snapshot ; le résoudre ici demanderait un état partagé que rien d'autre dans le projet n'a.
  Limite à écrire, pas à corriger.
- **Le front.** Rien à changer : un 429 emprunte le chemin d'échec déjà posé par UX-1, et les
  pollers réessaient à cadence fixe (`setTimeout` reprogrammé), donc sans amplification. Un message
  distinguant « trop de requêtes » d'une panne serait du confort pour un cas qu'un usager ne doit
  jamais voir.
- **Le quota par ligne, par session ou par jeton.** Il n'y a pas d'identité dans cette API et rien
  n'en demande.
- **`limit_conn`.** Le nombre de connexions simultanées n'est pas ce qui menace ici ; le débit
  requêtes l'est.

## 8. Filet de tests

Deux régimes, comme pour PERF-3 : du TDD strict sur le code neuf, un filet de non-régression sur
l'existant.

**Unitaire (`RateLimiterTest`)**, avec un `Clock` mutable comme `ResponseCacheTest` :

- sous le budget, l'appel passe ; au franchissement, il est refusé ;
- le passage à la fenêtre suivante rouvre le budget ;
- deux IP distinctes ont des compteurs indépendants ;
- **le balayage évince réellement** — une entrée d'une fenêtre périmée disparaît (assertion sur une
  taille exposée en visibilité paquet, comme `ResponseCache.size()`) ;
- le nombre de secondes restant jusqu'à la fin de la fenêtre, pour le `Retry-After`.

**Intégration (`RateLimitIT`)**, `@SpringBootTest(properties = "app.ratelimit.requests-per-minute=3")`
— propriété distincte, donc **contexte Spring séparé**, donc aucun effet sur les 53 IT existants :

- avec un `remoteAddr` public posé sur la requête MockMvc, la quatrième requête reçoit **429**,
  avec `Retry-After` présent et le corps `ErrorResponse` attendu (`errorCode`, `path`, `status`) ;
- `mapidf.ratelimit.rejected` s'est incrémenté ;
- une requête depuis `127.0.0.1` n'est **jamais** refusée, quel qu'en soit le nombre — c'est
  l'exemption du § 3, et c'est ce qui protège la suite existante.

**Ce que les tests ne peuvent pas voir**, à vérifier à la main sur la pile Docker : le
`RemoteIpValve` est posé par Tomcat, donc **MockMvc ne l'exerce pas** — la résolution de
`X-Forwarded-For` ne se constate que sur un vrai connecteur. De même, `limit_req` et la fermeture du
port 8100 ne se vérifient que sur la pile lancée. À faire figurer dans le plan comme une étape de
recette explicite, pas comme un test.

## 9. Conséquences sur la documentation

- **`docs/roadmap.md`** : SEC-3 passe à *fait*, avec les deux écarts au constat (§ en-tête). La
  mention du micro-cache hérité est réécrite en décision de sortie de périmètre, avec le chiffre qui
  la motive. Le point 3 de l'« Ordre recommandé » perd SEC-3.
- **`CLAUDE.md`** : la distinction `native` / `framework` (§ 2.3) et le fait qu'un `Filter` ne
  puisse pas réutiliser l'`ApiExceptionHandler` (§ 2.5) sont deux pièges non évidents qui y ont leur
  place. Le budget et sa raison (« arrête une boucle, n'arbitre pas ») aussi.
- **`README.md`** : le comportement en 429 et le budget, dans la section « API » ; et la fermeture
  du port 8100 dans « Ports : rien à configurer », qui décrit la publication actuelle.
- **`.env.example`** : inchangé, et c'est délibéré (§ 3).
