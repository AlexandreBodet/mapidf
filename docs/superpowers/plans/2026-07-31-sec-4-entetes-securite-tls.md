# SEC-4 — En-têtes de sécurité, TLS préparé et images durcies — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rendre la pile Docker de MapIDF servable ailleurs que sur la machine d'un développeur : CSP stricte et en-têtes de sécurité sur toutes les réponses nginx, cache correct, aucun conteneur en root, `HEALTHCHECK` des deux côtés, et un scénario TLS documenté prêt à recevoir un terminateur.

**Architecture:** Tout se joue dans quatre fichiers de configuration ([frontend/nginx.conf](../../../frontend/nginx.conf), les deux `Dockerfile`, [docker-compose.yml](../../../docker-compose.yml)) plus un script de vérification et deux fichiers de documentation. Aucun code applicatif — ni Java, ni TypeScript — n'est touché. Le script `scripts/check-headers.sh` est écrit **en premier** : c'est le test qui échoue, et il reste ensuite comme garde-fou.

**Tech Stack:** nginx (image `nginxinc/nginx-unprivileged:alpine`), Docker / docker compose, `eclipse-temurin:25-jre`, bash + curl pour la vérification.

## Global Constraints

Copiées de la spec [2026-07-31-sec-4-entetes-securite-tls-design.md](../specs/2026-07-31-sec-4-entetes-securite-tls-design.md). Elles s'appliquent à **toutes** les tâches.

- **`docker compose up --build` reste UNE seule commande**, sans nouvelle variable obligatoire. L'utilisateur le lance tous les jours.
- **L'URL du développeur ne change pas** : le front reste sur `http://localhost:8080`.
- **`script-src` reste `'self'`** — jamais `unsafe-inline`, jamais `unsafe-eval`.
- **Valeur exacte de la CSP** (une seule ligne, à reproduire caractère pour caractère dans `security-headers.conf` **et** dans `scripts/check-headers.sh`) :
  ```
  default-src 'none'; script-src 'self'; style-src 'self' 'unsafe-inline'; style-src-elem 'self'; style-src-attr 'unsafe-inline'; img-src 'self' data: blob: https://tiles.openfreemap.org; connect-src 'self' https://tiles.openfreemap.org; worker-src blob:; frame-ancestors 'none'; base-uri 'none'; form-action 'none'; object-src 'none'
  ```
- **`proxy_pass http://backend:8100;` reste SANS slash final.** Il transmet l'URI complète, `/api` étant le context-path du backend. Ajouter le slash casse tous les appels.
- **Aucun `add_header Cache-Control` dans `location /api/`** : `add_header` **ajoute**, il ne remplace pas, et le backend envoie déjà son propre `Cache-Control` sur `/network`.
- **`server.forward-headers-strategy` n'est PAS activé côté Spring.** On envoie les `X-Forwarded-*`, on ne les fait pas confiance. Ne pas toucher à `application.yml`.
- **`uid` du backend : 10001**, utilisateur `mapidf`. Valeur fixe, pas au choix de l'implémenteur.
- **Commentaires sobres** : uniquement le « pourquoi » non-évident, 1 à 2 lignes. Ce projet en écrit peu.
- **Ne jamais démarrer ni arrêter les applications de l'utilisateur.** Les conteneurs lancés pour vérifier utilisent le port **8081** et un nom explicite, et sont retirés à la fin de chaque tâche. Ne pas lancer `docker compose up` — le port 8100 est peut-être occupé par le backend que l'utilisateur fait tourner dans son IDE.

## Structure des fichiers

| Fichier | Responsabilité | Tâche |
|---|---|---|
| `scripts/check-headers.sh` | **Créé.** Interroge une pile lancée, sort en erreur si un en-tête manque ou a changé | 1 |
| `frontend/security-headers.conf` | **Créé.** Les seuls `add_header` de sécurité, inclus par chaque `location` | 2 |
| `frontend/nginx.conf` | **Modifié.** Réécrit : compression, cache par chemin, en-têtes de proxy, `server_tokens off` | 2, 3 |
| `frontend/Dockerfile` | **Modifié.** Image non privilégiée, copie de `security-headers.conf`, `HEALTHCHECK` | 3 |
| `docker-compose.yml` | **Modifié.** Port du conteneur front, attente d'un backend sain | 3, 4 |
| `backend/Dockerfile` | **Modifié.** Cache de couche Maven, utilisateur non-root, `HEALTHCHECK` | 4 |
| `README.md` | **Modifié.** Section « Mise en ligne : ce qu'un terminateur TLS doit faire » | 5 |
| `CLAUDE.md` | **Modifié.** Les pièges porteurs (héritage de `add_header`, slash de `proxy_pass`) | 5 |
| `docs/roadmap.md` | **Modifié.** SEC-4 → `fait`, mention de la part de SEC-6 absorbée | 5 |

---

## Task 1 : le garde-fou des en-têtes (le test qui échoue)

**Files:**
- Create: `scripts/check-headers.sh`

**Interfaces:**
- Consumes: rien.
- Produces: `scripts/check-headers.sh [base-url]` — défaut `http://localhost:8080`. Sort `0` si tous les en-têtes attendus sont présents et exacts, `1` sinon, en listant chaque écart. Les tâches 2 et 3 s'en servent comme critère de réussite.

- [ ] **Step 1 : écrire le script**

Créer `scripts/check-headers.sh` avec exactement ce contenu :

```bash
#!/usr/bin/env bash
# Garde-fou des en-têtes de sécurité (chantier SEC-4). Interroge une pile lancée et sort en
# erreur si un en-tête manque ou a changé de valeur.
#
#   scripts/check-headers.sh [base-url]        # défaut : http://localhost:8080
#
# Les valeurs attendues sont dupliquées ici : c'est un test, pas une source. Si la conf nginx
# change délibérément, ce fichier change avec elle — c'est précisément l'intérêt.
set -uo pipefail

BASE="${1:-http://localhost:8080}"
failures=0

CSP="default-src 'none'; script-src 'self'; style-src 'self' 'unsafe-inline'; style-src-elem 'self'; style-src-attr 'unsafe-inline'; img-src 'self' data: blob: https://tiles.openfreemap.org; connect-src 'self' https://tiles.openfreemap.org; worker-src blob:; frame-ancestors 'none'; base-uri 'none'; form-action 'none'; object-src 'none'"

# Corps ignoré : seuls les en-têtes comptent. Noms ramenés en minuscules pour comparer sans
# dépendre de la casse choisie par le serveur.
headers_of() {
  curl -sS -o /dev/null -D - "$1" | tr -d '\r' \
    | awk 'NR>1 && /:/ {
        name = tolower(substr($0, 1, index($0, ":") - 1));
        value = substr($0, index($0, ":") + 2);
        print name "\t" value
      }'
}

expect() { # fichier nom valeur_attendue
  local actual
  actual="$(awk -F'\t' -v n="$2" '$1 == n { print $2 }' "$1")"
  if [[ "$actual" == "$3" ]]; then
    printf '  ✓ %s\n' "$2"
  else
    printf '  ✗ %s\n      attendu : %s\n      obtenu  : %s\n' "$2" "$3" "${actual:-<absent>}"
    failures=$((failures + 1))
  fi
}

reject() { # fichier motif libellé
  if grep -qiE "$2" "$1"; then
    printf '  ✗ %s : %s\n' "$3" "$(grep -iE "$2" "$1" | head -1)"
    failures=$((failures + 1))
  else
    printf '  ✓ %s\n' "$3"
  fi
}

security_headers() { # fichier
  expect "$1" content-security-policy "$CSP"
  expect "$1" x-content-type-options "nosniff"
  expect "$1" x-frame-options "DENY"
  expect "$1" referrer-policy "no-referrer"
  expect "$1" permissions-policy "geolocation=(), camera=(), microphone=()"
  expect "$1" cross-origin-opener-policy "same-origin"
  expect "$1" strict-transport-security "max-age=31536000"
}

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

echo "→ $BASE/ (page d'entrée)"
headers_of "$BASE/" > "$tmp/root"
security_headers "$tmp/root"
expect "$tmp/root" cache-control "no-cache"
# `server_tokens off` laisse « nginx » mais retire la version : c'est ce chiffre qu'on refuse.
# Le motif n'attend pas de « : » — headers_of sépare le nom de la valeur par une tabulation.
reject "$tmp/root" '^server.*nginx/[0-9]' "la version de nginx n'est pas annoncée"

# Le nom des assets est haché par Vite : on le lit dans la page plutôt que de le figer ici.
asset="$(curl -sS "$BASE/" | grep -o '/assets/[^"]*\.js' | head -1)"
if [[ -z "$asset" ]]; then
  echo "  ✗ aucun /assets/*.js trouvé dans la page : le front est-il bien bâti ?"
  failures=$((failures + 1))
else
  echo "→ $asset (asset haché)"
  headers_of "$BASE$asset" > "$tmp/asset"
  security_headers "$tmp/asset"
  expect "$tmp/asset" cache-control "public, max-age=31536000, immutable"
fi

# Un asset absent doit porter les en-têtes malgré son 404 : c'est ce que `always` garantit, et
# c'est le cas qu'on oublie toujours de vérifier.
echo "→ /assets/absent-de-toute-facon.js (404)"
headers_of "$BASE/assets/absent-de-toute-facon.js" > "$tmp/missing"
security_headers "$tmp/missing"

if (( failures > 0 )); then
  printf '\n%d écart(s). La conf nginx et ce script ne disent pas la même chose.\n' "$failures"
  exit 1
fi
printf '\nTous les en-têtes attendus sont là.\n'
```

- [ ] **Step 2 : le rendre exécutable**

```bash
chmod +x scripts/check-headers.sh
```

- [ ] **Step 3 : bâtir l'image front actuelle et la lancer sur 8081**

`--add-host backend:127.0.0.1` est indispensable : `proxy_pass http://backend:8100` fait résoudre `backend` **au démarrage** de nginx, et sans cet alias le conteneur refuse de démarrer hors de la pile compose.

```bash
docker build -t mapidf-front-sec4 frontend/
docker run -d --rm --name mapidf-front-check --add-host backend:127.0.0.1 -p 8081:80 mapidf-front-sec4
sleep 2
```

- [ ] **Step 4 : lancer le script et vérifier qu'il ÉCHOUE**

Run: `scripts/check-headers.sh http://localhost:8081; echo "code de sortie : $?"`

Expected: `code de sortie : 1`, et une liste d'écarts où **tous** les en-têtes de sécurité sont `<absent>`, le `cache-control` de la racine est `<absent>`, et la version de nginx **est** annoncée (`server: nginx/1.2x.y`). C'est l'état des lieux du § 2 de la spec, constaté et non supposé.

- [ ] **Step 5 : arrêter le conteneur de vérification**

```bash
docker rm -f mapidf-front-check
```

- [ ] **Step 6 : commit**

```bash
git add scripts/check-headers.sh
git commit -m "test(sec-4): garde-fou des en-têtes de sécurité, rouge sur la conf actuelle"
```

---

## Task 2 : la conf nginx — CSP, en-têtes, cache, compression, proxy

**Files:**
- Create: `frontend/security-headers.conf`
- Modify: `frontend/nginx.conf` (réécriture complète du fichier, 10 lignes aujourd'hui)
- Modify: `frontend/Dockerfile` (une ligne `COPY` à ajouter)

**Interfaces:**
- Consumes: `scripts/check-headers.sh` (tâche 1) comme critère de réussite.
- Produces: `frontend/security-headers.conf`, inclus par chaque bloc `location` de `nginx.conf` et copié dans l'image à `/etc/nginx/security-headers.conf`. La tâche 3 change son `listen` et son image de base, rien d'autre.

- [ ] **Step 1 : créer `frontend/security-headers.conf`**

```nginx
# Inclus par CHAQUE bloc `location`, et ce n'est pas une maladresse : dès qu'un `location` pose
# son propre `add_header`, nginx cesse d'hériter de ceux du serveur. Les poser une seule fois au
# niveau `server` ferait disparaître toute la sécurité des réponses de /assets/ — silencieusement,
# puisque ce bloc a son propre Cache-Control.
#
# `always` : les en-têtes doivent couvrir aussi les réponses d'erreur (404, 502).
#
# Chaque directive de la CSP est justifiée par une mesure au § 3 de
# docs/superpowers/specs/2026-07-31-sec-4-entetes-securite-tls-design.md. Ne pas « simplifier » :
# le triple `style-src` est un repli pour les navigateurs sans style-src-elem/-attr, et
# `worker-src blob:` est vital à MapLibre, qui crée son worker par createObjectURL.
add_header Content-Security-Policy "default-src 'none'; script-src 'self'; style-src 'self' 'unsafe-inline'; style-src-elem 'self'; style-src-attr 'unsafe-inline'; img-src 'self' data: blob: https://tiles.openfreemap.org; connect-src 'self' https://tiles.openfreemap.org; worker-src blob:; frame-ancestors 'none'; base-uri 'none'; form-action 'none'; object-src 'none'" always;
add_header X-Content-Type-Options "nosniff" always;
add_header X-Frame-Options "DENY" always;
add_header Referrer-Policy "no-referrer" always;
# À rouvrir pour UX-5 (« trains autour de moi »), qui aura besoin de geolocation=(self).
add_header Permissions-Policy "geolocation=(), camera=(), microphone=()" always;
add_header Cross-Origin-Opener-Policy "same-origin" always;
# Ignoré par les navigateurs sur une origine http: — sans effet aujourd'hui, correct le jour où un
# terminateur TLS est devant (cf. README, section mise en ligne).
add_header Strict-Transport-Security "max-age=31536000" always;
```

- [ ] **Step 2 : réécrire `frontend/nginx.conf`**

Remplacer tout le fichier par :

```nginx
server {
  listen 80;
  server_tokens off;

  gzip on;
  gzip_vary on;
  gzip_min_length 1024;
  gzip_types text/css application/javascript application/json image/svg+xml;

  # Assets hachés par Vite : leur nom change avec leur contenu, donc ils sont immuables. Pas de
  # repli sur index.html ici — un asset absent doit répondre 404, pas du HTML déguisé en script.
  location /assets/ {
    include /etc/nginx/security-headers.conf;
    add_header Cache-Control "public, max-age=31536000, immutable" always;
    root /usr/share/nginx/html;
    try_files $uri =404;
  }

  # `proxy_pass` SANS slash final : il transmet l'URI complète, /api étant le context-path du
  # backend. Et aucun Cache-Control ici — `add_header` ajoute au lieu de remplacer, donc en poser
  # un doublerait celui que le backend envoie déjà sur /network.
  location /api/ {
    include /etc/nginx/security-headers.conf;
    proxy_http_version 1.1;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_pass http://backend:8100;
  }

  location / {
    include /etc/nginx/security-headers.conf;
    add_header Cache-Control "no-cache" always;
    root /usr/share/nginx/html;
    try_files $uri /index.html;
  }
}
```

- [ ] **Step 3 : copier le nouveau fichier dans l'image**

Dans `frontend/Dockerfile`, après la ligne `COPY nginx.conf /etc/nginx/conf.d/default.conf`, ajouter :

```dockerfile
COPY security-headers.conf /etc/nginx/security-headers.conf
```

- [ ] **Step 4 : rebâtir, relancer, et vérifier que le script PASSE**

```bash
docker build -t mapidf-front-sec4 frontend/
docker run -d --rm --name mapidf-front-check --add-host backend:127.0.0.1 -p 8081:80 mapidf-front-sec4
sleep 2
scripts/check-headers.sh http://localhost:8081; echo "code de sortie : $?"
```

Expected: `code de sortie : 0`, toutes les lignes en `✓`, y compris la section 404 (qui prouve que `always` fonctionne) et la section asset (qui prouve que l'inclusion par `location` fonctionne — si elle manquait, les en-têtes de sécurité y seraient absents alors que la racine les aurait).

- [ ] **Step 5 : vérifier que la compression fonctionne**

```bash
curl -sS -H 'Accept-Encoding: gzip' -o /dev/null -D - "http://localhost:8081$(curl -sS http://localhost:8081/ | grep -o '/assets/[^"]*\.js' | head -1)" | grep -i content-encoding
```

Expected: `content-encoding: gzip`.

- [ ] **Step 6 : arrêter le conteneur**

```bash
docker rm -f mapidf-front-check
```

- [ ] **Step 7 : commit**

```bash
git add frontend/nginx.conf frontend/security-headers.conf frontend/Dockerfile
git commit -m "feat(sec-4): CSP stricte, en-têtes de sécurité, cache et compression nginx"
```

---

## Task 3 : le conteneur front ne tourne plus en root

**Files:**
- Modify: `frontend/Dockerfile` (image de base finale, `HEALTHCHECK`, `EXPOSE`)
- Modify: `frontend/nginx.conf` (`listen 8080`)
- Modify: `docker-compose.yml` (port du conteneur)

**Interfaces:**
- Consumes: `frontend/security-headers.conf` et `frontend/nginx.conf` (tâche 2), `scripts/check-headers.sh` (tâche 1).
- Produces: une image front qui écoute **8080** dans le conteneur et tourne en uid 101. Le port publié sur l'hôte reste **8080** : `http://localhost:8080` ne change pas pour le développeur.

- [ ] **Step 1 : passer à l'image non privilégiée**

Dans `frontend/Dockerfile`, remplacer la seconde étape. Avant :

```dockerfile
FROM nginx:alpine
COPY --from=build /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY security-headers.conf /etc/nginx/security-headers.conf
EXPOSE 80
```

Après :

```dockerfile
# Image non privilégiée : uid 101, et un port non privilégié (8080) est sa raison d'être — d'où
# le `listen 8080` de la conf. Le port publié sur l'hôte, lui, reste 8080.
FROM nginxinc/nginx-unprivileged:alpine
COPY --from=build /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY security-headers.conf /etc/nginx/security-headers.conf
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --retries=3 \
  CMD wget -q --spider http://127.0.0.1:8080/ || exit 1
```

- [ ] **Step 2 : faire écouter la conf sur 8080**

Dans `frontend/nginx.conf`, remplacer `listen 80;` par `listen 8080;`.

- [ ] **Step 3 : publier le nouveau port du conteneur**

Dans `docker-compose.yml`, service `frontend`, remplacer `ports: ["8080:80"]` par `ports: ["8080:8080"]`.

- [ ] **Step 4 : rebâtir et vérifier l'utilisateur, la santé et les en-têtes**

```bash
docker build -t mapidf-front-sec4 frontend/
docker run -d --rm --name mapidf-front-check --add-host backend:127.0.0.1 -p 8081:8080 mapidf-front-sec4
sleep 12
docker exec mapidf-front-check id
docker inspect --format '{{.State.Health.Status}}' mapidf-front-check
scripts/check-headers.sh http://localhost:8081; echo "code de sortie : $?"
```

Expected: `uid=101(nginx)` — donc **pas** `uid=0` ; `healthy` ; `code de sortie : 0`.

- [ ] **Step 5 : arrêter le conteneur**

```bash
docker rm -f mapidf-front-check
```

- [ ] **Step 6 : commit**

```bash
git add frontend/Dockerfile frontend/nginx.conf docker-compose.yml
git commit -m "feat(sec-4): front en uid 101 sur nginx-unprivileged, avec HEALTHCHECK"
```

---

## Task 4 : le backend en non-root, avec un HEALTHCHECK réel et un build caché

**Files:**
- Modify: `backend/Dockerfile` (réécriture complète, 9 lignes aujourd'hui)
- Modify: `docker-compose.yml` (le front attend un backend **sain**)

**Interfaces:**
- Consumes: rien des tâches précédentes.
- Produces: une image backend en uid 10001 avec un `HEALTHCHECK` qui interroge `/actuator/health`. C'est lui qui rend possible le `condition: service_healthy` du service `frontend`.

**Contexte pour l'implémenteur :** l'image `eclipse-temurin:25-jre` n'a **ni curl ni wget** (mesuré), mais elle a `/bin/bash`. D'où le `/dev/tcp`, qui interroge le vrai endpoint de santé sans ajouter de paquet dans l'image finale. Et le backend n'écrit qu'un fichier temporaire, par `Files.createTempFile` dans `/tmp` ([GtfsStaticLoader.java:91](../../../backend/src/main/java/com/mapidf/gtfs/GtfsStaticLoader.java#L91)) : `/tmp` est accessible en écriture à tout utilisateur, le passage en non-root ne casse donc pas le chargement GTFS.

- [ ] **Step 1 : réécrire `backend/Dockerfile`**

Remplacer tout le fichier par :

```dockerfile
FROM eclipse-temurin:25-jdk AS build
WORKDIR /app
# Dépendances résolues AVANT le code : une modification de classe ne refait plus le
# téléchargement complet. `go-offline` ne ramène pas tout (certains plugins se résolvent à
# l'exécution) — la couche n'est pas hermétique, elle est utile.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw -B -q dependency:go-offline
COPY src/ src/
RUN ./mvnw -B -q -DskipTests package

FROM eclipse-temurin:25-jre
WORKDIR /app
# uid fixe et hors de la plage système de la distribution : les droits d'un volume monté un jour
# restent prévisibles.
RUN groupadd --system --gid 10001 mapidf \
 && useradd --system --uid 10001 --gid 10001 --no-create-home --shell /usr/sbin/nologin mapidf
COPY --from=build --chown=10001:10001 /app/target/*.jar app.jar
USER 10001
EXPOSE 8100 9100
# L'image n'a ni curl ni wget, mais elle a bash : /dev/tcp interroge le vrai endpoint de santé
# sans ajouter un paquet dans l'image finale. On lit la ligne de statut HTTP, pas le corps : un
# sous-composant "UP" isolé y suffirait même agrégat DOWN. `start-period` généreux — au premier
# démarrage, le backend charge le GTFS complet (~125 Mo) avant d'être prêt.
HEALTHCHECK --interval=10s --timeout=3s --start-period=90s --retries=3 CMD \
  bash -c 'exec 3<>/dev/tcp/127.0.0.1/9100; printf "GET /actuator/health HTTP/1.0\r\n\r\n" >&3; head -1 <&3 | grep -qE "^HTTP/1\.[01] 200"'
ENTRYPOINT ["java", "-jar", "app.jar"]
```

- [ ] **Step 2 : vérifier le snippet de santé contre un backend réel**

Le backend de l'utilisateur tourne peut-être déjà, Actuator sur 9100. Si oui, ce test prouve le snippet pour de vrai (une simple lecture, rien n'est modifié) :

```bash
bash -c 'exec 3<>/dev/tcp/127.0.0.1/9100; printf "GET /actuator/health HTTP/1.0\r\n\r\n" >&3; head -1 <&3 | grep -qE "^HTTP/1\.[01] 200"' && echo "snippet OK" || echo "snippet en échec ou backend absent"
```

Expected: `snippet OK`. Si le message est `snippet en échec ou backend absent`, vérifier d'abord que quelque chose écoute (`curl -s localhost:9100/actuator/health`) : sans backend local, ce contrôle est simplement reporté à la recette finale, ce n'est pas un échec de la tâche.

- [ ] **Step 3 : bâtir l'image et vérifier l'utilisateur**

Ce premier build télécharge toutes les dépendances Maven dans la nouvelle couche : compter
plusieurs minutes. C'est le prix payé **une fois** pour que les suivants soient rapides.

```bash
docker build -t mapidf-back-sec4 backend/
docker run --rm --entrypoint id mapidf-back-sec4
```

Expected: `uid=10001(mapidf) gid=10001(mapidf)` — donc **pas** `uid=0(root)`, et un gid explicite plutôt
que celui choisi par défaut par `useradd`.

**Si `dependency:go-offline` échoue** (il est connu pour buter sur certains plugins), ne pas le
neutraliser par un `|| true` — remplacer la ligne par la variante plus étroite, qui résout les
dépendances et les plugins sans prétendre à l'hermétisme :

```dockerfile
RUN ./mvnw -B -q dependency:resolve dependency:resolve-plugins
```

Puis reprendre ce Step 3. Signaler le remplacement dans le rapport de tâche : le commentaire du
Dockerfile devra dire ce qui est réellement mis en cache.

- [ ] **Step 4 : vérifier que le HEALTHCHECK est bien enregistré dans l'image**

```bash
docker inspect --format '{{json .Config.Healthcheck}}' mapidf-back-sec4
```

Expected: un JSON contenant `/dev/tcp/127.0.0.1/9100` et `"Interval":10000000000`. Le fonctionnement du healthcheck dans la pile complète se vérifie à la recette finale — un conteneur backend seul ne démarre pas (ni base, ni clé PRIM).

- [ ] **Step 5 : vérifier que le cache de couche Maven fonctionne**

```bash
touch backend/src/main/java/com/mapidf/rt/RealtimePoller.java
docker build -t mapidf-back-sec4 backend/ 2>&1 | tail -20
```

Expected: la sortie montre `CACHED` sur les étapes `COPY .mvn/`, `COPY mvnw pom.xml` et `RUN ./mvnw -B -q dependency:go-offline`, et ne réexécute que `COPY src/` et le `package`. C'est tout l'objet du découpage.

- [ ] **Step 6 : faire attendre un backend sain par le front**

Dans `docker-compose.yml`, service `frontend`, remplacer `depends_on: [backend]` par :

```yaml
    depends_on:
      backend:
        condition: service_healthy
```

- [ ] **Step 7 : vérifier que le compose reste valide**

Run: `docker compose config --quiet; echo "code de sortie : $?"`

Expected: `code de sortie : 0`, sans avertissement. (Cette commande ne démarre rien.)

- [ ] **Step 8 : commit**

```bash
git add backend/Dockerfile docker-compose.yml
git commit -m "feat(sec-4): backend en uid 10001, HEALTHCHECK réel et cache de couche Maven"
```

---

## Task 5 : la documentation qui rend le chantier durable

**Files:**
- Modify: `README.md` (nouvelle section, placée juste avant « Données, sources et licences »)
- Modify: `CLAUDE.md` (deux pièges à ajouter aux conventions de code)
- Modify: `docs/roadmap.md` (SEC-4 → `fait`, part de SEC-6 absorbée)

**Interfaces:**
- Consumes: tout ce que les tâches 2 à 4 ont posé.
- Produces: rien qu'une autre tâche consomme.

- [ ] **Step 1 : la section « mise en ligne » du README**

Insérer dans `README.md`, juste **avant** la ligne `## Données, sources et licences` :

```markdown
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
4. **Restreindre le port API** (8100 par défaut) : contrairement à l'Actuator, la pile le publie sur toutes les interfaces. Un accès direct à ce port contourne nginx et tous ses en-têtes de sécurité. Sur une machine exposée, le restreindre à `127.0.0.1` ou au réseau interne de la pile.
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
```

- [ ] **Step 2 : les deux pièges dans CLAUDE.md**

Dans `CLAUDE.md`, section « Conventions de code », ajouter à la fin de la liste :

```markdown
- **nginx : `add_header` n'est PAS hérité** dès qu'un bloc `location` en pose un lui-même. C'est
  pourquoi les en-têtes de sécurité vivent dans `frontend/security-headers.conf`, **inclus par
  chaque `location`** : les poser une seule fois au niveau `server` les ferait disparaître des
  réponses de `/assets/` (qui a son propre `Cache-Control`), silencieusement. Et pas de
  `Cache-Control` dans `/api/` : `add_header` ajoute au lieu de remplacer, il doublerait celui du
  backend sur `/network`. Le script vérifie l'inclusion par `location` et les en-têtes sur 404 (où seul `always` les fait passer).
- **`proxy_pass http://backend:8100;` sans slash final est volontaire** : il transmet l'URI
  complète, `/api` étant le context-path du backend. Le « corriger » casse tous les appels.
```

- [ ] **Step 3 : la feuille de route**

Dans `docs/roadmap.md`, remplacer le statut de la ligne SEC-4 (`**en cours** — [spec](...)`) par :

```
**fait** — [spec](superpowers/specs/2026-07-31-sec-4-entetes-securite-tls-design.md). CSP stricte (`script-src 'self'`, sans `unsafe-eval`), en-têtes de sécurité inclus par chaque `location`, cache un an sur les assets hachés et `no-cache` sur `index.html`, gzip, `server_tokens off`, `X-Forwarded-*` vers le back, et `scripts/check-headers.sh` qui échoue si un en-tête disparaît. Absorbe la part « Dockerfile » de SEC-6 : front en uid 101 (`nginx-unprivileged`), backend en uid 10001, `HEALTHCHECK` des deux côtés, résolution Maven en couche cachée. **Reste à SEC-6** : scan de dépendances. **TLS non terminé par la pile** : le scénario est documenté dans le README, la décision revient à l'hébergeur
```

- [ ] **Step 4 : vérifier que les liens ajoutés pointent quelque part**

```bash
test -f docs/superpowers/specs/2026-07-31-sec-4-entetes-securite-tls-design.md && echo "spec OK"
test -x scripts/check-headers.sh && echo "script OK et exécutable"
grep -c "security-headers.conf" CLAUDE.md frontend/nginx.conf frontend/Dockerfile
```

Expected: `spec OK`, `script OK et exécutable`, et un compte non nul pour les trois fichiers.

- [ ] **Step 5 : commit**

```bash
git add README.md CLAUDE.md docs/roadmap.md
git commit -m "docs(sec-4): scénario TLS, pièges nginx porteurs, feuille de route"
```

---

## Recette finale (à faire par l'utilisateur, pas par un implémenteur)

La CSP ne se valide que dans un navigateur : c'est le seul niveau capable de dire si une tuile,
un glyphe ou le worker de MapLibre est bloqué. Les tâches ci-dessus ne lancent jamais la pile
complète — le port 8100 est peut-être occupé par le backend de l'IDE.

**Si le backend de l'IDE tourne**, deux options avant de lancer la pile : l'arrêter, ou déplacer
les ports publiés en posant `SERVER_PORT=8200` et `MANAGEMENT_SERVER_PORT=9200` dans le `.env`
(mécanisme déjà documenté au README, section « Ports »).

```bash
docker compose up --build
```

1. http://localhost:8080 — la console du navigateur ne montre **aucune** violation CSP. Critère
   bloquant : une seule violation invalide le chantier.
2. Carte : déplacement, zoom avant **et** arrière (le raster Natural Earth n'apparaît qu'aux zooms
   lointains, les noms de stations qu'à partir du zoom 13 — les deux doivent s'afficher).
3. Une fiche station, un train suivi, le sélecteur de lignes, une perturbation dépliée.
4. Fenêtre réduite sous 720 px : la feuille repliable, et le « ⓘ » de l'attribution.
5. `docker compose ps` : `db`, `backend` et `frontend` tous **healthy**.
6. `docker compose exec backend id` → `uid=10001` ; `docker compose exec frontend id` → `uid=101`.
   Aucun `uid=0`.
7. `scripts/check-headers.sh` → tout en `✓`.
8. Rechargement forcé de la page : les assets viennent du cache (`200 (from disk cache)` ou `304`
   sur `index.html`).
