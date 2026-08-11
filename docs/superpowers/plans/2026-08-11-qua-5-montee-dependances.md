# QUA-5 — Montée des dépendances : plan d'implémentation

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ramener l'outillage et les bibliothèques du front à leur dernière version, monter
`commons-csv` côté back, et le prouver — `npm audit` à zéro, tests au même compte, chunk MapLibre
toujours distinct, CSP intacte en navigateur.

**Architecture:** Onze étapes, un commit chacune, révocable seule. L'ordre place la chaîne de build
puis le harnais de test **avant** le code applicatif, pour qu'un test rouge accuse la bibliothèque
qu'on vient de monter et pas l'outil qui la mesure. MapLibre est isolé dans son propre commit :
c'est le seul saut que les tests ne couvrent pas, donc le seul dont la recette navigateur doit
pouvoir désigner la responsabilité.

**Tech Stack:** npm 11 / Node 24 LTS · vite 8 (rolldown) + `@vitejs/plugin-react` 6 · vitest 4 +
jsdom 27 + Testing Library · React 19 · MapLibre GL 6 · TypeScript 7 (facultatif) · Maven / Spring
Boot 4.1 / Java 25.

**Spec :** [2026-08-11-qua-5-montee-dependances-design.md](../specs/2026-08-11-qua-5-montee-dependances-design.md)

## Global Constraints

- **Node ≥ 20.19.0 ou ≥ 22.12.0** est exigé par vite 8 et `@vitejs/plugin-react` 6. Cible retenue :
  **Node 24 LTS**, sur le poste **et** dans l'image. Le poste mesuré est en `v20.17.0` : sous le
  plancher.
- **`@vitejs/plugin-react` 6 exige `vite: ^8`** — les deux montent dans le **même** commit.
- **Un commit par étape**, et tout revert doit emporter `package-lock.json`.
- **`./mvnw verify` exige `JAVA_HOME=~/.jdks/temurin-25.0.4`** — le `java` du shell est en 21.
- **La pile Docker est démarrée par le développeur, pas par l'assistant.** Les `docker build`, qui
  construisent sans démarrer, font exception (autorisé pour ce chantier).
- **Ne pas toucher au contournement `setFilter` de `VehicleLayer`**, ni au style inline (QUA-8), ni
  aux versions gérées par le BOM Spring Boot.
- **Aucun changement de comportement visible** n'est attendu. Ce chantier ne livre que des versions.

## Référence mesurée le 2026-08-11 — c'est à ça qu'on compare

`npm test` :

```
Test Files  11 passed (11)
     Tests  69 passed (69)
  Duration  1.18s
```

`npm run build` (vite 5.4.21, 63 modules) :

```
dist/index.html                     0.58 kB │ gzip:   0.36 kB
dist/assets/index-BY95_B2E.css     65.65 kB │ gzip:   9.31 kB
dist/assets/index-BqiSzbnD.js     175.34 kB │ gzip:  56.78 kB
dist/assets/maplibre-D6KsYbmY.js  801.82 kB │ gzip: 217.63 kB
```

Les empreintes (`BY95_B2E`…) changeront à chaque étape — c'est normal et sans intérêt. **Ce qui
compte est qu'un fichier `dist/assets/maplibre-*.js` existe encore**, et que sa taille reste du
même ordre (~800 kB brut). S'il disparaît, le `manualChunks` a été ignoré en silence.

`npm audit` : 7 vulnérabilités (4 moderate, 2 high, 1 critical).

---

### Task 0 : Prérequis — Node 24 sur le poste

**Files:** aucun. Ce n'est pas un commit, c'est une porte.

**Interfaces:**
- Produces: un `node -v` ≥ 24 dont toutes les tâches suivantes dépendent.

- [ ] **Step 1 : Constater le plancher**

```bash
node -v   # attendu avant montée : v20.17.0
npm -v
```

- [ ] **Step 2 : Installer Node 24 LTS**

C'est au développeur de le faire, avec son gestionnaire habituel (nvm, asdf, paquet système).
L'assistant ne touche pas au poste. Exemple avec nvm :

```bash
nvm install 24 && nvm use 24 && nvm alias default 24
```

- [ ] **Step 3 : Vérifier**

```bash
node -v   # doit afficher v24.x
npm -v    # npm 11.x est fourni avec Node 24
```

Attendu : `v24.` en préfixe. **Si `node -v` reste en 20.17, ne pas continuer** — la tâche 2
échouera à l'installation avec une erreur `EBADENGINE`.

- [ ] **Step 4 : Réinstaller l'arbre sous le nouveau Node**

```bash
cd frontend && rm -rf node_modules && npm ci
npm test
```

Attendu : `11 passed (11)` / `69 passed (69)`. Ce point de contrôle isole « Node a changé » de
« une dépendance a changé » : si les tests rougissent **ici**, c'est Node, et rien d'autre n'a
encore bougé.

---

### Task 1 : Node 24 dans l'image front

**Files:**
- Modify: `frontend/Dockerfile:1`

**Interfaces:**
- Consumes: rien.
- Produces: une image de build dont le Node satisfait les engines de vite 8 (tâche 2).

- [ ] **Step 1 : Modifier la ligne de base**

Dans `frontend/Dockerfile`, remplacer :

```dockerfile
FROM node:20 AS build
```

par :

```dockerfile
FROM node:24 AS build
```

Rien d'autre ne change : l'étage d'exécution reste `nginxinc/nginx-unprivileged:alpine`, le
`HEALTHCHECK` et le port 8080 sont inchangés.

- [ ] **Step 2 : Construire l'image sans démarrer la pile**

```bash
cd /home/abodet/workspace/perso/MapIDF
docker build -t mapidf-front-check ./frontend
```

Attendu : build réussi. C'est encore vite 5 à ce stade, donc l'étape ne prouve que la
compatibilité de Node 24 avec l'arbre actuel.

- [ ] **Step 3 : Commit**

```bash
git add frontend/Dockerfile
git commit -m "build(qua-5): image front sur Node 24 LTS

Plancher exigé par vite 8 et @vitejs/plugin-react 6 (^20.19.0 || >=22.12.0),
posé avant la montée elle-même pour que l'image et le poste soient sur la
même version."
```

---

### Task 2 : vite 5 → 8 et `@vitejs/plugin-react` 4 → 6

C'est la tâche la plus délicate du chantier : trois majeures de vite, deux du plugin, et un
changement de bundler (rollup → **rolldown**).

**Files:**
- Modify: `frontend/package.json` (devDependencies), `frontend/package-lock.json`
- Modify (si nécessaire) : `frontend/vite.config.ts:19-25`

**Interfaces:**
- Consumes: Node 24 (tâches 0 et 1).
- Produces: un `npm run build` qui émet toujours `dist/assets/maplibre-*.js` ; un `vite.config.ts`
  dont la clé de découpage est celle que rolldown honore.

- [ ] **Step 1 : Constater le vert avant de bouger**

```bash
cd frontend
npm test && npm run build && ls -1 dist/assets/
```

Attendu : `69 passed`, et `dist/assets/` contient un `maplibre-*.js`. C'est la ligne de base de
cette tâche.

- [ ] **Step 2 : Monter les deux paquets ensemble**

```bash
npm install --save-dev vite@8 @vitejs/plugin-react@6
```

Attendu : installation sans `ERESOLVE`. **Ne pas ajouter `--legacy-peer-deps`** : si npm refuse,
c'est un vrai conflit de pairs qu'il faut lire, pas contourner.

- [ ] **Step 3 : Lancer le build et lire ce que rolldown dit de la config**

```bash
npm run build
```

Trois issues possibles, et la réponse à chacune :

1. **Build vert avec `dist/assets/maplibre-*.js`** → parfait, passer au step 5.
2. **Build en erreur sur `build.rollupOptions`** → échec bruyant, donc bénin. Migrer la clé vers
   son équivalent rolldown en gardant le commentaire d'origine de `vite.config.ts` (il explique
   *pourquoi* MapLibre est isolé : « il pèse l'essentiel du bundle et bouge rarement »).
3. **Build vert MAIS aucun `maplibre-*.js` dans `dist/assets/`** → c'est le scénario dangereux du
   § 4.1 de la spec : la clé a été acceptée et ignorée. Passer au step 4.

- [ ] **Step 4 : (seulement si le chunk a disparu) rétablir le découpage**

Le contrat à retrouver est : un fichier séparé pour MapLibre, du même ordre de grandeur (~800 kB
brut / ~218 kB gzip). Adapter `frontend/vite.config.ts` en conservant le commentaire existant :

```ts
    build: {
      rollupOptions: {
        // MapLibre pèse l'essentiel du bundle et bouge rarement : chunk séparé →
        // mieux caché entre deux déploiements et chunk applicatif plus léger.
        output: { manualChunks: { maplibre: ["maplibre-gl"] } },
      },
    },
```

Si rolldown a renommé l'option, écrire la forme qu'il documente et **ajouter une ligne de
commentaire disant que la clé a changé de nom en vite 8** — c'est exactement le genre de détail que
la prochaine session paiera cher.

- [ ] **Step 5 : Vérifier le chunk explicitement**

```bash
ls -1 dist/assets/maplibre-*.js && du -h dist/assets/maplibre-*.js
```

Attendu : exactement un fichier, de l'ordre de 800 kB. **Un `No such file` fait échouer la
tâche** — ne pas continuer, revenir au step 4.

- [ ] **Step 6 : Vérifier les tests et le serveur de dev**

```bash
npm test
```

Attendu : `69 passed (69)`. Vitest 2 accepte vite 8 ? Son peer déclaré est `^6 || ^7 || ^8` pour
vitest 4 — **vitest 2 peut refuser vite 8**. Si `npm test` casse ici avec une erreur de résolution
ou d'API vite, ce n'est pas un défaut : c'est le signal qu'il faut faire les tâches 2 et 3 dans le
même commit. Dans ce cas, enchaîner immédiatement sur la tâche 3 et ne commiter qu'une fois les
deux faites, avec un message qui dit pourquoi elles sont couplées.

Puis, le serveur de dev et son proxy :

```bash
npm run dev
```

Ouvrir `http://localhost:5173`, vérifier que la carte s'affiche et que l'onglet réseau montre des
appels `/api/network` et `/api/vehicles` en 200 (le backend doit tourner). Arrêter avec Ctrl-C.

- [ ] **Step 7 : Commit**

```bash
git add frontend/package.json frontend/package-lock.json frontend/vite.config.ts
git commit -m "build(qua-5): vite 5 → 8 et plugin-react 4 → 6

Couplés par le peer 'vite: ^8' de @vitejs/plugin-react 6 : ils ne peuvent pas
monter séparément. Vite 8 remplace rollup par rolldown, d'où la vérification
explicite que dist/assets/ porte toujours un chunk MapLibre distinct — une
clé manualChunks acceptée et ignorée aurait fondu 800 ko dans le bundle
principal sans un mot."
```

---

### Task 3 : vitest 2 → 4 et jsdom 26 → 27

**Files:**
- Modify: `frontend/package.json` (devDependencies), `frontend/package-lock.json`
- Lire, et ne modifier qu'en cas de rupture : `frontend/src/test/setup.ts`,
  `frontend/src/ui/Sheet.test.tsx`

**Interfaces:**
- Consumes: vite 8 (tâche 2).
- Produces: un harnais à jour, prérequis des tâches 5 et 6.

- [ ] **Step 1 : Monter les deux paquets**

```bash
cd frontend
npm install --save-dev vitest@4 jsdom@27
```

- [ ] **Step 2 : Lancer les tests**

```bash
npm test
```

Attendu : `Test Files 11 passed (11)` / `Tests 69 passed (69)`.

**Le compte est le critère, pas la couleur.** Vitest 4 change les valeurs par défaut de découverte
de fichiers ; « 8 fichiers verts » serait un échec déguisé en succès. Si le compte a baissé,
chercher quel fichier n'est plus ramassé avant toute autre chose.

- [ ] **Step 3 : Relire les trois stubs de `setup.ts` à la lumière de jsdom 27**

Ouvrir `frontend/src/test/setup.ts`. Il pose trois stubs que jsdom 26 imposait : absence de
`ResizeObserver`, `setPointerCapture` qui **lève**, et toute mesure à 0.

Vérifier ce que jsdom 27 fournit désormais :

```bash
node -e "const {JSDOM}=require('jsdom');const w=new JSDOM().window;
console.log('ResizeObserver:', typeof w.ResizeObserver);
console.log('PointerEvent:', typeof w.PointerEvent);
console.log('setPointerCapture:', typeof w.Element.prototype.setPointerCapture);"
```

**Ne rien retirer dans cette tâche**, quel que soit le résultat. Un stub devenu inutile est
inoffensif ; un stub qui masque désormais une vraie implémentation ne l'est pas, mais le corriger
pendant qu'on migre le moteur de test ferait perdre le seul repère disponible. **Consigner le
résultat** — il partira dans CLAUDE.md à la tâche 10 et deviendra une ligne pour QUA-8.

Garder en tête que le piège `timeStamp: 0` de `Sheet.test.tsx` reste vrai quelle que soit la
version de jsdom : React calcule `event.timeStamp || Date.now()`.

- [ ] **Step 4 : Commit**

```bash
git add frontend/package.json frontend/package-lock.json
git commit -m "test(qua-5): vitest 2 → 4 et jsdom 26 → 27

Le harnais monte avant le code applicatif, pour qu'un test rouge aux étapes
suivantes accuse React ou MapLibre et pas l'outil qui les mesure. Compte de
tests inchangé : 69 en 11 fichiers — vitest 4 change ses défauts de découverte,
et des fichiers escamotés en silence auraient donné un vert menteur."
```

---

### Task 4 : `npm audit fix` — le résidu transitif

**Files:**
- Modify: `frontend/package-lock.json` (et `package.json` s'il y a lieu)

**Interfaces:**
- Consumes: vite 8 (tâche 2), qui a déjà éteint la chaîne `esbuild → vite → vitest`.
- Produces: `npm audit` à zéro, ce qui clôt le volet front de SEC-6.

- [ ] **Step 1 : Voir ce qu'il reste**

```bash
cd frontend && npm audit
```

Attendu : la chaîne esbuild/vite a disparu (tâche 2). Il ne devrait rester que `nanoid` (high) et
`postcss` (moderate), tous deux transitifs.

- [ ] **Step 2 : Corriger sans casser de majeure**

```bash
npm audit fix
```

**Sans `--force`.** `--force` installerait des majeures non planifiées et ferait sortir la tâche de
son périmètre. S'il reste une vulnérabilité que seul `--force` corrige, **ne pas la forcer** :
la consigner et la traiter dans la tâche 10 comme un reliquat documenté.

- [ ] **Step 3 : Vérifier**

```bash
npm audit
npm test
npm run build && ls -1 dist/assets/maplibre-*.js
```

Attendu : `found 0 vulnerabilities`, `69 passed (69)`, et le chunk MapLibre présent.

- [ ] **Step 4 : Commit**

```bash
git add frontend/package.json frontend/package-lock.json
git commit -m "fix(qua-5): npm audit à zéro (nanoid, postcss)

Clôt le volet front de SEC-6 : la chaîne esbuild → vite → vitest était déjà
tombée avec vite 8, il ne restait que deux transitifs. Sans --force, qui
installerait des majeures hors périmètre."
```

---

### Task 5 : React 18 → 19

**Files:**
- Modify: `frontend/package.json` (dependencies + devDependencies), `frontend/package-lock.json`
- Lire, et ne modifier qu'en cas de rupture : `frontend/src/main.tsx`,
  `frontend/src/map/useVehicles.ts`, `frontend/src/map/useNetwork.ts`,
  `frontend/src/api/useDisruptions.ts`, `frontend/src/map/MapView.tsx`

**Interfaces:**
- Consumes: le harnais de la tâche 3.
- Produces: rien de nouveau pour les tâches suivantes.

- [ ] **Step 1 : Monter React et ses types ensemble**

```bash
cd frontend
npm install react@19 react-dom@19
npm install --save-dev @types/react@19 @types/react-dom@19
```

Les quatre montent en même temps : des `@types/react` 18 sur un React 19 produiraient des erreurs
de typage qui n'ont rien à voir avec le code.

- [ ] **Step 2 : Lancer les tests**

```bash
npm test
```

Attendu : `69 passed (69)`. `@testing-library/react` 16.3.2 déclare
`react: ^18.0.0 || ^19.0.0` : il n'a pas besoin de bouger.

- [ ] **Step 3 : Compiler**

```bash
npm run build
```

Attendu : vert, et le chunk MapLibre toujours là. Si `tsc` remonte des erreurs, elles viendront
des `@types/react` 19 (les types de `ReactNode` et des refs s'y sont resserrés) — les corriger
dans les composants concernés, sans changer de comportement.

- [ ] **Step 4 : Chercher le double-effet de `StrictMode`**

`src/main.tsx` monte l'application sous `StrictMode`, qui invoque deux fois les effets en
développement. Les hooks à nettoyage sont l'endroit où un nettoyage incomplet se voit.

```bash
npm run dev
```

Dans l'onglet réseau du navigateur, avec le backend démarré : compter les appels `/api/vehicles`.
Attendu : **un par cycle de ~4 s, pas deux**. Deux appels par cycle signalent un `useEffect` dont
le nettoyage ne retire pas le timer ou l'écouteur — inspecter `useVehicles.ts`, puis
`useNetwork.ts` et `useDisruptions.ts`, et enfin les paires `map.on` / `map.off` de `MapView.tsx`.

- [ ] **Step 5 : Commit**

```bash
git add frontend/package.json frontend/package-lock.json frontend/src
git commit -m "feat(qua-5): React 18 → 19

Les quatre paquets (react, react-dom et leurs @types) montent ensemble : des
types 18 sur un runtime 19 produiraient des erreurs sans rapport avec le code.
Vérifié sous StrictMode qu'il reste un seul appel /vehicles par cycle — le
double-montage des effets est là où un nettoyage incomplet se voit."
```

---

### Task 6 : MapLibre GL 4 → 6, et la recette navigateur

Le seul saut que ni les tests ni le build ne couvrent. Il est seul dans son commit pour que toute
casse visuelle lui soit imputable.

**Files:**
- Modify: `frontend/package.json` (dependencies), `frontend/package-lock.json`
- Lire, et ne modifier qu'en cas de rupture d'API : `frontend/src/map/MapView.tsx`,
  `frontend/src/map/VehicleLayer.ts`, `frontend/src/map/useNetwork.ts`,
  `frontend/src/map/useVehicles.ts`, `frontend/src/map/mapReady.ts`,
  `frontend/src/map/attribution.ts`, `frontend/src/App.tsx`

**Interfaces:**
- Consumes: tout ce qui précède.
- Produces: rien pour les tâches suivantes ; c'est la fin du front applicatif.

- [ ] **Step 1 : Monter MapLibre**

```bash
cd frontend
npm install maplibre-gl@6
```

- [ ] **Step 2 : Compiler et lire les erreurs de typage**

```bash
npm run build
```

L'API réellement utilisée par le projet est étroite et stable : `on` / `off`, `addLayer`,
`addSource`, `getSource`, `setFilter`, `addImage` / `hasImage` / `removeImage`, `getCanvas`,
`setPadding` / `getPadding`, `easeTo`, `jumpTo`, `getBounds`, `getZoom`, `addControl`.
Les types importés sont `Map`, `GeoJSONSource` et `FilterSpecification`.

Corriger les erreurs de typage **sans changer de conception**. En particulier : **ne pas remplacer
le `setFilter` de `VehicleLayer` par du `feature-state`**, même si MapLibre 6 a corrigé le
`feature index out of bounds` qui l'a motivé — le commentaire d'en-tête de `journeyRefFilter`
explique pourquoi, et ce serait un autre chantier.

- [ ] **Step 3 : Vérifier tests et chunk**

```bash
npm test
ls -1 dist/assets/maplibre-*.js && du -h dist/assets/maplibre-*.js
```

Attendu : `69 passed (69)`, et le chunk toujours présent (sa taille aura bougé, c'est normal).

- [ ] **Step 4 : Commit avant la recette**

```bash
git add frontend/package.json frontend/package-lock.json frontend/src
git commit -m "feat(qua-5): MapLibre GL 4 → 6

Deux majeures sur le moteur de rendu, seules dans leur commit : c'est le seul
saut du chantier qu'aucun test ne couvre, donc le seul dont la recette
navigateur doit pouvoir désigner la responsabilité."
```

- [ ] **Step 5 : Construire la pile**

```bash
cd /home/abodet/workspace/perso/MapIDF
docker compose build
```

- [ ] **Step 6 : Recette navigateur — à dérouler par le développeur**

L'assistant ne démarre pas la pile. Lui fournir cette liste, et attendre son retour.

```bash
docker compose up
```

Sur `http://localhost:8080`, **sur Chrome puis sur Firefox** :

1. **Fond de carte** : les tuiles openfreemap se chargent, les tracés des 16 lignes sont dessinés,
   les pastilles du sélecteur sont là.
2. **Véhicules** : les trains sont rendus **et se déplacent** entre deux polls. L'interpolation au
   `requestAnimationFrame` est ce qu'une majeure de MapLibre casse le plus discrètement — des
   trains figés qui sautent toutes les 4 s est un échec, pas un détail.
3. **Sélection** : clic sur un train → anneau de sélection ; clic sur une station → fiche et
   passages.
4. **Perturbations** : gravité sur les pastilles (couleur + glyphe), anneaux sur les stations non
   desservies.
5. **Sous 720 px** (réduire la fenêtre) : la feuille se replie, ses trois crans répondent au geste,
   la fraîcheur s'affiche sur la poignée.
6. **Attribution** : au-dessus de 720 px, la mention de source est dépliée en bas à droite ; sous
   720 px, elle passe en `compact` **et** en haut à droite. Ce n'est pas cosmétique — articles 5.4
   et 5.7 de la Licence Mobilité.
7. **Console** : **aucune violation CSP**. C'est l'objet principal de la recette : une origine
   externe nouvelle ne se voit ni au build, ni en `npm run dev`.

- [ ] **Step 7 : Garde-fou des en-têtes, pile démarrée**

```bash
scripts/check-headers.sh
```

Attendu : vert. La CSP attendue est dupliquée dans le script ; si MapLibre 6 exige une directive de
plus (`worker-src`, `child-src` et `img-src blob:` y sont déjà), c'est
`frontend/security-headers.conf` **et** le script qui changent ensemble, dans un commit séparé qui
dit quelle ressource l'a imposé.

---

### Task 7 : Back — `commons-csv` 1.11.0 → 1.14.1

**Files:**
- Modify: `backend/pom.xml:31`

**Interfaces:**
- Consumes: rien. Cette tâche est indépendante des six premières et peut se faire à tout moment.
- Produces: rien.

- [ ] **Step 1 : Modifier la version**

Dans `backend/pom.xml`, remplacer :

```xml
<dependency><groupId>org.apache.commons</groupId><artifactId>commons-csv</artifactId><version>1.11.0</version></dependency>
```

par :

```xml
<dependency><groupId>org.apache.commons</groupId><artifactId>commons-csv</artifactId><version>1.14.1</version></dependency>
```

- [ ] **Step 2 : Vérifier, IT comprises**

```bash
cd backend
JAVA_HOME=~/.jdks/temurin-25.0.4 ./mvnw verify
```

Attendu : `BUILD SUCCESS`. `commons-csv` sert au parsing GTFS en streaming
(`GtfsStaticLoader`) ; les IT Testcontainers chargent une base vraie, donc elles exercent ce
chemin.

- [ ] **Step 3 : Commit**

```bash
git add backend/pom.xml
git commit -m "build(qua-5): commons-csv 1.11.0 → 1.14.1

Seule montée réelle côté back : Spring Boot 4.1.0, Java 25 et tous les plugins
Maven sont déjà à leur dernière version. Le versions-plugin ne la montrait pas,
masquée derrière l'artefact daté 20110211, lexicalement supérieur à toute la
série 1.x."
```

---

### Task 8 : ESLint

**Files:**
- Create: `frontend/eslint.config.js`
- Modify: `frontend/package.json` (devDependencies + script `lint`), `frontend/package-lock.json`

**Interfaces:**
- Consumes: l'arbre monté des tâches 2 à 6.
- Produces: un script `npm run lint`.

- [ ] **Step 1 : Installer**

```bash
cd frontend
npm install --save-dev eslint @eslint/js typescript-eslint eslint-plugin-react-hooks
```

`@eslint/js` est explicite : la configuration l'importe directement (`js.configs.recommended`), et
s'appuyer sur le fait que `typescript-eslint` le tire en transitif casserait au premier hissage
d'arbre différent.

- [ ] **Step 2 : Créer `frontend/eslint.config.js`**

```js
// Configuration délibérément minimale (QUA-5). Le premier passage porte sur 3 665 lignes jamais
// lintées : une règle qui produirait un flot de corrections mécaniques se désactive ici plutôt que
// de mêler un reformatage massif à une migration de dépendances. Le formatage viendra avec QUA-8.
import js from "@eslint/js";
import tseslint from "typescript-eslint";
import reactHooks from "eslint-plugin-react-hooks";

export default tseslint.config(
  { ignores: ["dist/", "node_modules/"] },
  js.configs.recommended,
  ...tseslint.configs.recommended,
  {
    files: ["**/*.{ts,tsx}"],
    plugins: { "react-hooks": reactHooks },
    rules: reactHooks.configs.recommended.rules,
  },
);
```

- [ ] **Step 3 : Ajouter le script**

Dans `frontend/package.json`, ajouter à `scripts` :

```json
    "lint": "eslint .",
```

- [ ] **Step 4 : Premier passage**

```bash
npm run lint
```

Deux cas :

- **Peu de signalements** → les corriger, sans changer de comportement.
- **Un flot de signalements mécaniques d'une même règle** → désactiver **cette règle** dans
  `eslint.config.js`, avec un commentaire d'une ligne disant pourquoi. Ne pas se lancer dans un
  reformatage : c'est précisément ce qui a fait sortir ESLint de QUA-3.

- [ ] **Step 5 : Vérifier qu'on n'a rien cassé**

```bash
npm run lint && npm test && npm run build
```

Attendu : les trois verts, `69 passed (69)`.

- [ ] **Step 6 : Commit**

```bash
git add frontend/eslint.config.js frontend/package.json frontend/package-lock.json frontend/src
git commit -m "chore(qua-5): ESLint, configuration minimale

Arrive après les montées, pas avant : un linter ne détecte pas une rupture
d'API MapLibre ou React, et le mélanger au diff de migration rendrait illisible
ce qui a cassé à cause de quoi. Règles limitées à typescript-eslint recommended
et react-hooks — le formatage viendra avec QUA-8."
```

---

### Task 9 : TypeScript 5.9 → 7 — séparable et abandonnable

TS 7 est le port natif du compilateur, pas une majeure ordinaire. **Cette tâche a le droit
d'échouer** ; son abandon ne défait rien du chantier.

**Files:**
- Modify: `frontend/package.json` (devDependencies), `frontend/package-lock.json`
- Modify (si nécessaire) : `frontend/tsconfig.json`

**Interfaces:**
- Consumes: tout ce qui précède.
- Produces: rien. Aucune tâche ne dépend d'elle.

- [ ] **Step 1 : Monter**

```bash
cd frontend
npm install --save-dev typescript@7
```

- [ ] **Step 2 : Compiler**

```bash
npm run build
```

Le script est `tsc -b && vite build`. Le `tsconfig.json` actuel est court : `target: ES2020`,
`moduleResolution: bundler`, `jsx: react-jsx`, `strict: true`, `skipLibCheck: true`, `noEmit: true`.

- [ ] **Step 3 : Appliquer le critère d'abandon**

- **Vert du premier coup** → passer au step 4.
- **Erreurs corrigeables dans `tsconfig.json` ou dans des annotations de types** → corriger.
- **`tsc` exige de modifier du code applicatif** (changer une structure, un comportement, une
  logique) → **abandonner** :

```bash
git checkout -- frontend/package.json frontend/package-lock.json frontend/tsconfig.json
npm ci
```

Consigner ce qui bloquait — ça devient une ligne de roadmap à la tâche 10. Monter un compilateur ne
doit pas se payer en changements de produit.

- [ ] **Step 4 : Vérifier**

```bash
npm run build && npm test && npm run lint
```

Attendu : les trois verts, `69 passed (69)`.

- [ ] **Step 5 : Commit (si non abandonnée)**

```bash
git add frontend/package.json frontend/package-lock.json frontend/tsconfig.json
git commit -m "build(qua-5): TypeScript 5.9 → 7

Port natif du compilateur, monté en dernier et séparément pour rester
révocable. Critère respecté : aucune modification de code applicatif n'a été
nécessaire, seulement de la configuration et des types."
```

---

### Task 10 : Documentation et clôture

**Files:**
- Modify: `docs/roadmap.md` (lignes QUA-5 et SEC-6, et le point 2 de « Ordre recommandé »)
- Modify: `CLAUDE.md` (section « Conventions de code »)
- Modify: `README.md` (prérequis Node)

**Interfaces:**
- Consumes: les constats des tâches 3, 4 et 9.
- Produces: la trace.

- [ ] **Step 1 : `docs/roadmap.md` — QUA-5 passe à `fait`**

Remplacer le statut `à faire` de la ligne QUA-5 par `**fait**` suivi du lien vers la spec et du
résumé de ce qui a réellement été monté — en nommant l'écart avec le constat d'origine (la roadmap
annonçait « Vite 5 », c'était vite 5→8 avec `@vitejs/plugin-react` 4→6 en remorque). Mentionner
l'issue de la tâche 9 (TS 7 monté, ou abandonné et pourquoi).

- [ ] **Step 2 : `docs/roadmap.md` — amender SEC-6**

Son volet front est clos : les 7 vulnérabilités sont éteintes. Réécrire la ligne pour qu'il ne
reste que ce qui est encore ouvert — le **scan automatique** de dépendances, back compris. Ne pas
supprimer l'historique du constat du 2026-08-10 : le préciser comme résolu.

- [ ] **Step 3 : `docs/roadmap.md` — mettre à jour l'ordre recommandé**

Au point 1, ajouter QUA-5 à la liste barrée des chantiers faits. Au point 2, retirer la phrase
« QUA-5 peut se glisser avant QUA-8 » : il ne reste que QUA-8 puis UX-4. Retirer aussi le paragraphe
final « QUA-5 ne l'est plus », devenu sans objet.

- [ ] **Step 4 : `CLAUDE.md` — les deux outils qui sous-déclarent**

Ajouter dans « Conventions de code » une entrée courte, dans le ton des autres (le « pourquoi » non
évident, pas le mode d'emploi) :

- `npm outdated` **sous-déclare** : mesuré, il plafonnait vite à 6.4.3 quand son dist-tag `latest`
  était 8.2.1, et omettait entièrement `@vitejs/plugin-react`. Le contrôle fiable est
  `npm view <paquet> dist-tags`.
- Le **versions-plugin Maven** masque une montée derrière un artefact daté : `commons-csv` était
  annoncé `1.11.0 -> 20110211` (un artefact de 2011, lexicalement supérieur à toute la série 1.x),
  cachant 1.14.1. Croiser avec `maven-metadata.xml` de Maven Central.

- [ ] **Step 5 : `CLAUDE.md` — ce que jsdom 27 a changé, s'il y a lieu**

Si le relevé de la tâche 3 step 3 montre que jsdom 27 fournit désormais `PointerEvent`,
`ResizeObserver` ou un vrai `setPointerCapture`, l'écrire là où le piège est déjà documenté (la
puce « Tests front »), en précisant que les stubs et le `firePointer` de `Sheet.test.tsx` **n'ont
pas été retirés** et que c'est un angle pour QUA-8. Si rien n'a changé, ne rien écrire.

- [ ] **Step 6 : `README.md` — prérequis Node**

Indiquer **Node 24 LTS** comme prérequis du développement front, en donnant la raison (le plancher
`^20.19.0 || >=22.12.0` de vite 8), pour qu'un `npm install` qui échoue en `EBADENGINE` soit
diagnostiqué en dix secondes.

- [ ] **Step 7 : Recette de contrôle finale**

Après la tâche 9, la pile a changé une dernière fois. Reconstruire et redérouler la check-list de
la tâche 6 step 6, en version courte : carte, trains animés, sélection, feuille sous 720 px,
console sans violation CSP. Puis :

```bash
scripts/check-headers.sh
```

- [ ] **Step 8 : Vérification d'ensemble**

```bash
cd frontend && npm audit && npm test && npm run build && npm run lint
cd ../backend && JAVA_HOME=~/.jdks/temurin-25.0.4 ./mvnw verify
```

Attendu : `found 0 vulnerabilities`, `69 passed (69)`, un `dist/assets/maplibre-*.js`, lint vert,
`BUILD SUCCESS`.

- [ ] **Step 9 : Commit**

```bash
git add docs/roadmap.md CLAUDE.md README.md
git commit -m "docs(qua-5): chantier clos, et les deux outils de constat faussés

QUA-5 passe à fait et le volet front de SEC-6 se ferme (0 vulnérabilité).
CLAUDE.md gagne les deux pièges qui ont failli faire sous-dimensionner le
chantier : npm outdated plafonnait vite trois majeures trop bas et omettait
@vitejs/plugin-react, le versions-plugin Maven masquait commons-csv derrière un
artefact de 2011."
```
