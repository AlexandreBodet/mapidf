# SEC-4 — En-têtes de sécurité, TLS préparé et images durcies

*Document de conception — 2026-07-31*

Chantier [SEC-4](../../roadmap.md), élargi à la moitié bon marché de **SEC-6** : le durcissement
des images vit dans les mêmes fichiers que les en-têtes, et le séparer imposerait deux revues du
même `Dockerfile`. L'intention derrière la demande est de **structurer le projet et préparer sa
mise à disposition**, pas de livrer une fonction visible : ce chantier ne change rien à ce que
l'utilisateur voit.

## 1. Objectif et critères de réussite

Rendre la pile Docker servable ailleurs que sur la machine d'un développeur, sans avoir à y
revenir pour des questions de sécurité de base.

Est réussi si :

1. Toute réponse de nginx porte une CSP stricte, et **la carte fonctionne** : aucune violation
   dans la console, tuiles, sprites, glyphes, worker et icônes de véhicules compris.
2. `script-src` reste `'self'`, sans `unsafe-inline` ni `unsafe-eval`.
3. Les assets hachés sont cachés un an, `index.html` ne l'est pas : un déploiement se voit au
   rechargement.
4. Aucun conteneur de la pile ne tourne en root.
5. `docker compose up --build` reste **une seule commande**, sans nouvelle variable obligatoire.
6. Un développeur qui reprend le projet trouve dans le README ce qu'un terminateur TLS placé
   devant doit faire, sans avoir à le déduire.
7. `scripts/check-headers.sh` passe sur la pile lancée, et échoue si un en-tête disparaît.

## 2. État des lieux mesuré

[nginx.conf](../../../frontend/nginx.conf) fait dix lignes : `listen 80`, un `proxy_pass` nu vers
`backend:8100`, un `try_files` pour le SPA. Aucun en-tête, aucun cache, aucune compression,
`server_tokens` au défaut (qui annonce la version de nginx dans chaque réponse et dans ses pages
d'erreur).

| Fait mesuré | Conséquence |
|---|---|
| Ni `eval(` ni `new Function(` dans `index-*.js` **ni** dans `maplibre-*.js` | `script-src 'self'` suffit — pas d'`unsafe-eval`, la concession la plus coûteuse |
| `dist/index.html` ne contient **aucun** script ni style inline (Vite sort tout en fichiers avec `crossorigin`) | Pas besoin de nonce ni de hash pour le HTML |
| MapLibre n'appelle jamais `createElement("style")`, `insertRule` ni `styleSheet` — son CSS est empaqueté dans `index-*.css` par le `import` de `main.tsx` | L'`unsafe-inline` peut être confiné aux **attributs** `style`, un `<style>` injecté reste bloqué |
| `createObjectURL` × 2 dans MapLibre | Il faut `worker-src blob:` — **plus vrai depuis MapLibre 6, cf. note du § 3** |
| Le style `liberty` ne tire qu'un hôte : `tiles.openfreemap.org` (vecteur `/planet`, raster `/natural_earth`, sprites `/sprites`, glyphes `/fonts/*.pbf`) | Une seule origine externe à autoriser |
| `index-*.css` n'a **aucun** `@font-face`, et ses seuls `url()` sont des `data:image/svg+xml` (les icônes des contrôles MapLibre) | Pas de `font-src` à écrire ; `img-src data:` suffit à ces icônes |
| `eclipse-temurin:25-jre` n'a **ni curl ni wget**, mais a `/bin/bash` | Un `HEALTHCHECK` réel est possible sans ajouter de paquet |
| L'image backend n'a pas d'`USER` → elle tourne en **root** | Une évasion de processus démarre root dans le conteneur |
| `backend/Dockerfile` fait `COPY . .` avant tout `mvnw` | Chaque `--build` refait la résolution Maven : aucune couche cachée |

## 3. La CSP, directive par directive

Un seul en-tête, sur toutes les réponses :

```
default-src 'none';
script-src 'self';
style-src 'self' 'unsafe-inline';
style-src-elem 'self';
style-src-attr 'unsafe-inline';
img-src 'self' data: blob: https://tiles.openfreemap.org;
connect-src 'self' https://tiles.openfreemap.org;
child-src blob:;
worker-src blob:;
frame-ancestors 'none';
base-uri 'none';
form-action 'none';
object-src 'none';
```

> **Note du 2026-08-11 — trois directives de ce § ne sont plus celles qui sont servies.** La montée
> MapLibre 4 → 6 de [QUA-5](../../roadmap.md) a invalidé la mesure qui les justifiait : la v6 est
> ESM-only et charge son worker **par une URL same-origin** (`setWorkerUrl` dans `MapView.tsx`),
> jamais par `blob:`. `worker-src` et `child-src` sont donc passés à `'self'`, et `frame-src 'none'`
> a été ajouté — `child-src 'self'` rouvrait le cadrage que `blob:` fermait de fait, et
> `frame-ancestors` ne protège que contre *être* cadré. Le reste du raisonnement ci-dessous
> (repli `child-src` pour les moteurs sans `worker-src`, triple `style-src`, `img-src`,
> `connect-src`) reste valable. La CSP servie fait foi :
> [frontend/security-headers.conf](../../../frontend/security-headers.conf), dont le commentaire
> d'en-tête porte le détail de la mesure refaite.

- `default-src 'none'` : tout est refusé, chaque ouverture est justifiée ci-dessous. C'est ce qui
  rend l'oubli d'une directive **visible** plutôt que silencieux.
- `style-src` triple : les navigateurs qui connaissent `style-src-elem`/`-attr` (Chrome, Edge,
  Safari ≥ 15.4, Firefox ≥ 111) utilisent les directives précises et n'admettent l'inline que
  dans les **attributs** ; les plus anciens retombent sur `style-src`, plus permissif. La ligne
  de repli n'est donc pas une redondance : c'est ce qui évite qu'un vieux navigateur bloque tout
  le rendu.
- `img-src data: blob:` : MapLibre fabrique des images en mémoire (sprites découpés, icônes SDF
  des véhicules) et le favicon est un SVG servi par nous.
- `connect-src` couvre `/api` (même origine) et les tuiles, sprites et glyphes — tous récupérés
  en XHR/fetch, pas en `<img>` ni en `@font-face` : c'est pourquoi il n'y a **pas** de
  `font-src`.
- `worker-src blob:` sans `'self'` : MapLibre ne charge jamais de worker par URL. `child-src
  blob:` juste avant est le même genre de repli que le triple `style-src` : les moteurs qui
  ignorent `worker-src` (Safari < 15.5, Firefox < 58) retombent sur `child-src`, puis sur
  `default-src 'none'` — sans lui, ces navigateurs refusent le worker de MapLibre et la carte
  reste blanche.

**L'exception assumée** : `style-src-attr 'unsafe-inline'` est irréductible tant que le rendu est
en styles inline (décision d'origine du projet, ~15 composants). Elle n'ouvre quelque chose qu'à
qui possède déjà un point d'injection HTML, et le cas grave — l'exécution de script — reste fermé.
Sa suppression est un chantier à part, **QUA-8**, dont la vraie justification est ailleurs (voir
§ 9).

## 4. Les autres en-têtes

| En-tête | Valeur | Pourquoi |
|---|---|---|
| `X-Content-Type-Options` | `nosniff` | Empêche un `.json` d'être interprété comme du HTML |
| `X-Frame-Options` | `DENY` | Doublon volontaire de `frame-ancestors`, pour les navigateurs qui ignorent le second |
| `Referrer-Policy` | `no-referrer` | Les deux liens sortants (transport.data.gouv.fr, fabmob) n'ont aucun besoin de savoir d'où vient le clic |
| `Permissions-Policy` | `geolocation=(), camera=(), microphone=()` | L'appli n'utilise aucune des trois **aujourd'hui** |
| `Cross-Origin-Opener-Policy` | `same-origin` | Isole la fenêtre d'un `window.open` hostile, sans rien coûter |
| `Strict-Transport-Security` | `max-age=31536000` | Voir ci-dessous |
| `server_tokens` | `off` | La version de nginx n'est l'affaire de personne |

Trois décisions à assumer, et à ne pas « corriger » sans lire ceci :

- **HSTS est émis alors que rien ne sert en HTTPS.** Sur une origine `http:`, les navigateurs
  ignorent l'en-tête : il ne fait rien aujourd'hui et devient correct le jour où un terminateur
  TLS est devant. `includeSubDomains` est écarté — le domaine n'existe pas encore et la directive
  engagerait des sous-domaines inconnus ; `preload` l'est aussi, parce qu'il est en pratique
  irréversible.
- **COEP et CORP sont écartés.** `Cross-Origin-Embedder-Policy: require-corp` casserait les
  tuiles d'OpenFreeMap, qui ne portent pas d'en-tête CORP, pour un bénéfice nul ici : l'appli n'a
  ni `SharedArrayBuffer` ni mesure de temps haute résolution à protéger.
- **`geolocation=()` sera à rouvrir pour UX-5** (« trains autour de moi »). Une valeur à changer,
  notée dans la ligne UX-5 de la feuille de route.

## 5. Cache et compression

| Chemin | `Cache-Control` |
|---|---|
| `/assets/*` (haché par Vite) | `public, max-age=31536000, immutable` |
| `index.html` et tout le reste | `no-cache` |

`no-cache` et non `no-store` : le navigateur peut garder la réponse mais doit la revalider, donc
un déploiement se voit au rechargement sans retransférer le HTML à chaque navigation. Sans cette
paire, ou tout est retéléchargé, ou un `index.html` caché continue de réclamer des assets
supprimés.

`gzip on` sur les types texte (JS, CSS, JSON, SVG). Pas de brotli :
`nginxinc/nginx-unprivileged:alpine` n'embarque pas le module, et l'ajouter demanderait de
construire nginx — hors de proportion pour un gain de quelques pourcents sur des assets déjà
gzippés.

## 6. Le proxy vers le backend

```nginx
proxy_http_version 1.1;
proxy_set_header Host $host;
proxy_set_header X-Real-IP $remote_addr;
proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
proxy_set_header X-Forwarded-Proto $scheme;
```

Deux points à documenter dans la conf elle-même, parce que les deux ressemblent à des oublis :

- **`proxy_pass http://backend:8100;` reste sans slash final.** Il transmet l'URI complète,
  `/api/...`, et `/api` est précisément le context-path du backend. Ajouter le slash couperait le
  préfixe et casserait tous les appels.
- **`server.forward-headers-strategy` n'est PAS activé côté Spring.** L'application ne fabrique
  aucune URL absolue et ne journalise aucune IP client : faire confiance à ces en-têtes
  n'apporterait rien et rendrait l'IP client usurpable par quiconque atteint le backend
  directement. Le jour où SEC-3 (quota par IP) ou un journal d'accès en aura besoin, c'est là que
  la décision se renverse — et il faudra alors restreindre la confiance au seul proxy.

Conséquence acquise de SEC-1, à affirmer ici : nginx ne proxifie que `/api/` vers le port 8100,
et l'Actuator vit sur 9100. **`/actuator` est donc inatteignable au travers du front**, sans
règle de blocage à écrire. Un futur terminateur TLS ne doit pas le router davantage (§ 8).

## 7. Les images

**Front** — `nginxinc/nginx-unprivileged:alpine` au lieu de `nginx:alpine` : uid 101, écoute
**8080** par défaut (un port non privilégié est la raison d'être de l'image). Donc `listen 8080;`
dans la conf et `ports: ["8080:8080"]` dans le compose — **l'URL du développeur ne change pas**.
`HEALTHCHECK` par le wget de busybox, présent dans l'image.

**Backend** — un utilisateur système dédié (`mapidf`, **uid 10001** : au-delà de la plage des uid
système de la distribution, et fixe pour que les droits d'un volume monté un jour soient
prévisibles), le jar copié avec `--chown`, et `USER` avant l'`ENTRYPOINT`. Puis la résolution Maven **avant** le code, pour qu'un `--build` ne retélécharge
pas les dépendances quand seule une classe a changé :

```dockerfile
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw -B -q dependency:go-offline
COPY src/ src/
RUN ./mvnw -B -q -DskipTests package
```

`dependency:go-offline` est connu pour ne pas tout ramener (certains plugins se résolvent à
l'exécution) : la couche n'est donc pas un cache parfait, mais elle transforme un
`docker compose up --build` après une modification de code d'un téléchargement complet en une
recompilation. C'est le gain visé, pas l'hermétisme.

**`HEALTHCHECK` du backend** — l'image JRE n'a ni curl ni wget (mesuré), mais elle a bash, donc
`/dev/tcp` interroge le vrai endpoint de santé sans ajouter un paquet dans l'image finale :

```dockerfile
HEALTHCHECK --interval=10s --timeout=3s --start-period=90s --retries=3 CMD \
  bash -c 'exec 3<>/dev/tcp/127.0.0.1/9100; printf "GET /actuator/health HTTP/1.0\r\n\r\n" >&3; \
    head -1 <&3 | grep -qE "^HTTP/1\.[01] 200"'
```

Le contrôle lit la **ligne de statut HTTP**, pas le corps : avec `show-details: always`, le corps
liste aussi les sous-composants, et un `"status":"UP"` de sous-composant y suffirait à déclarer le
conteneur sain même agrégat `DOWN` (Spring Boot mappe `DOWN`/`OUT_OF_SERVICE` sur 503, donc le code
HTTP porte déjà l'agrégat).

`start-period` généreux : au premier démarrage, le backend télécharge et charge le GTFS complet
(~125 Mo) avant d'être prêt. Il ne doit pas être déclaré malade pendant ce temps.

Ce `HEALTHCHECK` ne conditionne cependant pas le démarrage de `frontend` dans le compose :
`depends_on: [backend]` suffit, nginx n'ayant besoin que du nom DNS. Un
`condition: service_healthy` ferait dépendre nginx d'un backend sain, or un `.env` incomplet le
fait boucler indéfiniment — nginx ne démarrerait alors jamais, privant le front du bandeau « Plan
en préparation » qu'il sait afficher seul (acquis d'UX-1). Le `HEALTHCHECK` reste utile à
`docker compose ps` et à un futur orchestrateur.

## 8. Le TLS : documenté et prêt, pas simulé

Aucun certificat n'est fabriqué ici, aucun service n'est ajouté à la pile : le TLS dépend d'un
hébergeur et d'un domaine qui ne sont pas choisis. Ce qui est livré, c'est une pile **prête à
être placée derrière un terminateur**, et une section du README qui dit ce que ce terminateur doit
faire :

1. Terminer le TLS et rediriger 80 → 443.
2. Transmettre `X-Forwarded-Proto: https` (la conf nginx le relaie déjà au backend).
3. **Ne pas router `/actuator`** — la pile ne l'expose que sur la loopback de l'hôte, un proxy mal
   réglé annulerait SEC-1.
4. Laisser passer les en-têtes de réponse de nginx sans les réécrire : HSTS devient actif à ce
   moment-là, sans changement de conf.

Le README dira aussi ce qui n'a **pas** besoin d'être traité : une seule origine sert le SPA et
l'API, donc aucune question de CORS ne se pose.

## 9. QUA-8, la suite logique de l'exception CSP

Nouvelle ligne de feuille de route, dont ce chantier est l'occasion mais pas la justification.

Supprimer les styles inline permettrait `style-src-attr 'none'`, un gain **de second ordre** :
l'attribut n'ouvre quelque chose qu'à qui a déjà une injection HTML. Le vrai coût du style inline
est ailleurs — un attribut `style` ne peut exprimer ni `:hover`, ni `:focus-visible`, ni
`@media`, ni `prefers-color-scheme`. **UX-4 est bloquée par là** : l'accès clavier exige des états
de focus visibles, et le thème sombre est hors d'atteinte.

Réserve portée par la ligne : ne pas l'attaquer **avant QUA-3**. Quinze composants convertis à la
main sans harnais de test, c'est le terrain des régressions muettes qu'UX-2 a documentées.

## 10. Vérification

Trois niveaux, du plus durable au plus manuel.

**Preuves statiques**, déjà obtenues et consignées au § 2 : aucun `eval`, aucun `<style>` injecté,
un seul hôte externe. Ce sont elles qui rendront la CSP défendable dans six mois, quand personne
ne se rappellera pourquoi telle directive est là.

**`scripts/check-headers.sh`** interroge une pile lancée (`http://localhost:8080` par défaut,
surchargeable par un argument) et **sort en erreur** si un en-tête manque ou a changé de valeur.
Il vérifie aussi les deux `Cache-Control` (un asset haché et `index.html`) et que la réponse ne
contient plus de `Server: nginx/x.y.z`. QUA-1 (CI) étant écarté, rien ne le lancera
automatiquement : il vaut mieux qu'une prose de README, pas mieux qu'une CI.

**Recette navigateur** — le seul niveau capable de valider la CSP :

1. `docker compose up --build`, puis http://localhost:8080.
2. Console : **aucune** violation CSP. C'est le critère bloquant.
3. Carte : déplacement, zoom avant/arrière (le raster Natural Earth n'apparaît qu'aux zooms
   lointains, les glyphes qu'à partir du zoom 13 — les deux doivent être vus).
4. Une fiche station, un train suivi, le sélecteur de lignes, une perturbation dépliée.
5. Mode étroit (< 720 px) : la feuille, et le « ⓘ » de l'attribution.
6. `docker compose ps` : les trois services `healthy` (`db`, `backend`, `frontend` ont chacun
   leur `HEALTHCHECK`).
7. `docker compose exec backend id` et `... exec frontend id` : aucun `uid=0`.

## 11. Hors périmètre, volontairement

- **Limite de débit nginx** (`limit_req`) : le front fait ~0,5 req/s par onglet et une limite mal
  réglée casserait le suivi temps réel. Le remède ordonné est PERF-3 (cache serveur de ~1 s) puis
  SEC-3, pas une valeur devinée dans cette conf.
- **Scan de dépendances** (Dependabot, `npm audit`, dependency-check) : reste dans SEC-6. Ce
  chantier ne prend de SEC-6 que ce qui vit dans les `Dockerfile`.
- **Suppression des styles inline** : QUA-8, § 9.
- **Certificats et hébergeur** : § 8.
- **CSP `report-uri`/`report-to`** : sans collecteur, un rapport ne va nulle part. La console du
  navigateur est le collecteur de ce projet.
