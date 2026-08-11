# QUA-5 — Montée des dépendances : front à jour, back vérifié

*Document de conception — 2026-08-11*

Chantier [QUA-5](../../roadmap.md). Le motif de report — « du bruit tant que rien ne casse » — est
tombé deux fois : les 7 vulnérabilités relevées le 2026-08-10 (cf. SEC-6) exigent toutes une montée
de majeure, et [QUA-3](2026-08-10-qua-3-outillage-front-design.md) a livré le harnais qui permet de
constater qu'une majeure n'a rien cassé.

## 1. Objectif et critères de réussite

Ramener l'outillage et les bibliothèques du front à leur dernière version, vérifier le back, et le
prouver autrement qu'en espérant.

Est réussi si, tout appliqué :

1. `npm audit` annonce **0 vulnérabilité**. C'est le seul critère qui ferme un point ouvert
   ailleurs (SEC-6, volet front).
2. `npm run build` passe **et `dist/assets/` contient toujours un chunk MapLibre distinct** — le
   découpage de [vite.config.ts](../../../frontend/vite.config.ts) survit au changement de bundler.
3. `npm test` est vert **avec le même nombre de tests qu'avant — 69 tests, 11 fichiers**, mesuré le
   2026-08-11 en 1,18 s. Une migration de moteur de test qui escamote des fichiers en silence est
   un faux vert : le compte est le garde-fou.
4. `./mvnw verify` passe, IT Testcontainers comprises.
5. `scripts/check-headers.sh` passe sur la pile Docker : la CSP n'a pas bougé.
6. La recette navigateur est propre **sur deux moteurs**, console vide de toute violation CSP.
7. Aucun changement de comportement visible. Ce chantier ne livre que des versions.

## 2. État des lieux mesuré

### 2.1 Les deux outils de constat sont faux

C'est le résultat le plus utile de l'exploration, et il conditionne tout le reste.

**`npm outdated` sous-déclare le retard.** Il annonce `vite 5.4.21 → 6.4.3` alors que le dist-tag
`latest` de vite est **8.2.1** (`previous: 7.3.6`), et il **omet entièrement**
`@vitejs/plugin-react`, installé en 4.7.0 quand 6.0.5 est publié. Le comportement persiste avec
`--prefer-online`, donc ce n'est pas un cache éventé. Le contrôle fiable est
`npm view <paquet> dist-tags`.

**Le versions-plugin Maven masque une vraie montée.** Il rapporte
`org.apache.commons:commons-csv 1.11.0 -> 20110211` : un artefact daté de 2011, lexicalement
supérieur à toute la série 1.x, qui cache 1.12, 1.13 et 1.14. La vraie cible, lue directement dans
`maven-metadata.xml` de Maven Central, est **1.14.1**.

Même forme des deux côtés : l'outil chargé de dire le retard le sous-estime, sans erreur ni
avertissement. Les deux partent dans CLAUDE.md.

### 2.2 Front — huit lignes, dont sept paquets à sauter une majeure ou plus

| Paquet | Actuel | Cible | Majeures |
|---|---|---|---|
| `vite` | 5.4.21 | **8.2.1** | 3 |
| `@vitejs/plugin-react` | 4.7.0 | **6.0.5** | 2 |
| `vitest` | 2.1.9 | 4.1.10 | 2 |
| `maplibre-gl` | 4.7.1 | 6.3.0 | 2 |
| `typescript` | 5.9.3 | 7.0.2 | 2 |
| `react` / `react-dom` (+ `@types`) | 18.3.1 | 19.2.8 | 1 |
| `jsdom` | 26.1.0 | 27.0.1 | 1 |
| Node (poste **et** image) | 20 | 24 LTS | — |

Les `@testing-library/*` sont déjà à jour et acceptent React 19 (`peerDependencies:
^18.0.0 || ^19.0.0`) : ils ne contraignent rien.

Trois contraintes dures :

- **`@vitejs/plugin-react` 6 exige `vite: ^8`.** Les deux forment un seul commit, pas deux.
- **`vitest` 4 accepte `vite ^6 || ^7 || ^8`** : il ne contraint pas l'ordre.
- **Le Node du poste est trop vieux** : `v20.17.0` mesuré, contre `^20.19.0 || >=22.12.0` exigé par
  vite 8 et plugin-react 6. Le chantier est bloqué tant que le poste n'est pas monté — ce n'est pas
  qu'une ligne de Dockerfile.

### 2.3 Ce que `npm audit` doit à qui

Les 7 vulnérabilités (4 moderate, 2 high, 1 critical) tiennent en deux paquets :

- la chaîne `esbuild → vite → vite-node / @vitest/mocker → vitest`, **éteinte par la montée de
  vite** ;
- `nanoid` et `postcss`, transitifs, réglés par `npm audit fix` seul.

React et MapLibre n'y sont pour rien : ce sont les montées de confort, pas de sécurité. Le constat
d'origine tient — toutes sont en devDependencies et rien n'atteint le navigateur — mais la seule
qui mordait en pratique, le serveur de dev de vite joignable par n'importe quelle page ouverte à
côté, disparaît ici.

### 2.4 Back — presque rien

Passé au versions-plugin (`display-parent-updates`, `display-dependency-updates`,
`display-plugin-updates`) :

- **Spring Boot 4.1.0 est la dernière version** — « The parent project is the latest version ».
- **Java 25** est la dernière LTS ; les images `eclipse-temurin:25-jdk` / `25-jre` sont à jour.
- **Tous les plugins Maven sont à leur dernière version**, Jacoco 0.8.15 compris.
- `jts-core` 1.20.0 est la dernière.
- **Seule montée réelle : `commons-csv` 1.11.0 → 1.14.1** (cf. § 2.1 pour pourquoi l'outil ne la
  montrait pas).

Tout le reste de ce que le plugin remonte (logback, jackson, elasticsearch…) est **géré par le BOM
Spring Boot**. Le surcharger désalignerait la version que Spring a testée : hors périmètre par
construction, pas par prudence.

## 3. Ordre d'exécution

Onze étapes, **un commit chacune, révocable seule** — à condition que le revert emporte aussi
`package-lock.json`, qui bouge à chaque fois.

| # | Étape | Ce qui la valide |
|---|---|---|
| 0 | Node 24 sur le poste | *prérequis, pas un commit* |
| 1 | `frontend/Dockerfile` : `node:20` → `node:24` | `docker build` |
| 2 | **vite 5→8 + plugin-react 4→6** (couplés) | `npm run build` + chunk MapLibre distinct + `npm run dev` avec proxy `/api` |
| 3 | vitest 2→4 + jsdom 26→27 | `npm test`, même compte de tests |
| 4 | `npm audit fix` (nanoid, postcss) | `npm audit` à zéro |
| 5 | react / react-dom + `@types` 18→19 | `npm test`, `npm run build` |
| 6 | **maplibre-gl 4→6** | build + **recette navigateur complète** |
| 7 | back : `commons-csv` 1.11.0→1.14.1 | `./mvnw verify` |
| 8 | ESLint (typescript-eslint + react-hooks) | `npm run lint` |
| 9 | TS 5.9→7 — *séparable, abandonnable* | `npm run build`, `npm test` |
| 10 | Docs : roadmap, CLAUDE.md, README | relecture |

L'ordre n'est pas cosmétique. Les étapes 2 et 3 remettent **le harnais** à niveau avant qu'on
touche au code applicatif, pour qu'un test rouge à l'étape 5 accuse React 19 et pas vitest. Et
l'étape 6 est seule de son espèce, pour que la recette navigateur n'ait qu'un suspect.

`./mvnw verify` exige `JAVA_HOME=~/.jdks/temurin-25.0.4` : le `java` du shell est en 21.

## 4. Points de vigilance

### 4.1 Le chunk MapLibre peut disparaître en silence

Vite 8 passe de rollup à **rolldown**, et [vite.config.ts](../../../frontend/vite.config.ts) porte
un `build.rollupOptions.output.manualChunks` qui isole MapLibre — « il pèse l'essentiel du bundle
et bouge rarement », dit son commentaire.

Le mauvais scénario n'est pas que la clé soit refusée : ce serait bruyant, donc bénin. C'est
qu'elle soit **acceptée et ignorée**, MapLibre fondu dans le bundle principal, sans un mot. D'où le
critère de réussite n° 2, qui se constate en listant `dist/assets/` — pas en lisant la config.

### 4.2 jsdom 27 peut combler ce que le harnais stubbe

`src/test/setup.ts` porte trois stubs que jsdom 26 impose : pas de `ResizeObserver`,
`setPointerCapture` qui **lève**, toute mesure à 0. Et `Sheet.test.tsx` construit ses événements à
la main (`firePointer`, un `MouseEvent` typé) parce que jsdom 26 n'a pas de `PointerEvent` global,
l'`Event` nu de repli perdant `clientY` en silence.

Si jsdom 27 fournit ces API, **on constate et on note, on ne retire rien** : réécrire un test
pendant qu'on migre son moteur, c'est perdre le seul repère dont on dispose. Ça devient une ligne
pour QUA-8. Un stub devenu inutile est inoffensif ; un stub qui **masque** désormais une vraie
implémentation ne l'est pas — c'est ce qu'il faut regarder à l'étape 3.

Le piège `timeStamp: 0` reste vrai quelle que soit la version : React calcule
`event.timeStamp || Date.now()`.

### 4.3 React 19 sous `StrictMode`

`main.tsx` monte l'application sous `StrictMode`. Les hooks à nettoyage — `useVehicles`,
`useNetwork`, `useDisruptions`, et les paires `map.on` / `map.off` de `MapView` — sont les endroits
où un nettoyage incomplet se voit. Symptôme observable en recette : **deux requêtes `/vehicles` par
cycle** au lieu d'une.

### 4.4 L'attribution est une obligation, pas un affichage

La mention de source et la nature estimée passent par l'API d'attribution de MapLibre
(`map/attribution.ts`, posée par `MapView.tsx`), avec le repli `compact` + `top-right` sous 720 px.
Si MapLibre 6 change cette API, ce sont les articles 5.4 et 5.7 de la Licence Mobilité qui sautent.
Point de recette explicite, séparé du reste.

### 4.5 La CSP ne se voit que dans un navigateur

CLAUDE.md est formel : une nouvelle origine externe ne se voit ni au `npm run build`, ni en
`npm run dev` (sans CSP) — seulement dans un navigateur sur la pile Docker. Une majeure de moteur
de rendu peut changer sa gestion des workers et des blobs. C'est le cœur de la recette, et la
raison pour laquelle elle ne peut pas être remplacée par une vérification automatique.

### 4.6 ESLint, premier passage sur 3 665 lignes jamais lintées

Configuration minimale : `typescript-eslint` recommended + `react-hooks`. Si une règle produit un
flot de corrections mécaniques sans valeur, **on la désactive** plutôt que de mêler un reformatage
massif à une migration — c'est l'argument qui avait sorti ESLint de QUA-3, il vaut encore ici.

### 4.7 Critère d'abandon pour TypeScript 7

TS 7 est une réécriture du compilateur (port natif), pas une majeure ordinaire. Critère explicite
pour renoncer : **si `tsc` exige de modifier du code applicatif** — et non des types ou de la
configuration — on reverse l'étape 9 et on ouvre une ligne de roadmap. Monter un compilateur ne
doit pas se payer en changements de produit.

## 5. Recette navigateur

Deux passages, sur `docker compose up --build` : un **complet juste après l'étape 6**, MapLibre
étant isolé pour que toute casse visuelle lui soit imputable ; un **de contrôle en fin de
chantier**. Le reste des étapes est couvert par `npm run build` et `npm test`.

La pile est démarrée par le développeur, pas par l'assistant — règle du projet. Les `docker build`
de l'étape 1, qui construisent sans démarrer, font exception.

Check-list :

1. Fond de carte openfreemap chargé, tracés des 16 lignes, pastilles du sélecteur
2. Véhicules rendus **et animés** — l'interpolation au `requestAnimationFrame` est ce que MapLibre
   peut casser le plus discrètement
3. Clic sur un train → halo de sélection ; clic sur une station → fiche et passages
4. Perturbations : gravité sur les pastilles, anneaux sur les stations non desservies
5. Sous 720 px : feuille repliable, ses trois crans au geste, fraîcheur sur la poignée, attribution
   passée en `compact` + `top-right` (§ 4.4)
6. **Console vide de toute violation CSP** (§ 4.5)
7. Les deux moteurs, Chrome et Firefox, comme pour
   [SEC-4](2026-07-31-sec-4-entetes-securite-tls-design.md)

## 6. Hors périmètre, et pourquoi

- **Spring Boot et les dépendances du BOM** : 4.1.0 est la dernière, et surcharger une version
  gérée désalignerait ce que Spring a testé (§ 2.4).
- **Le contournement `setFilter` de `VehicleLayer`** : même si MapLibre 6 a corrigé le
  `feature index out of bounds` qui l'a motivé, y revenir mélangerait une migration et un
  changement de conception. Le commentaire d'en-tête de `journeyRefFilter` reste la référence.
- **QUA-8 (sortir du style inline) et Prettier** : chantiers suivants, délibérément séparés.
- **Changer de gestionnaire de paquets** (pnpm, bun). Le seul reproche mesuré à npm est qu'il
  sous-déclare le retard (§ 2.1), et le remède coûte une commande. À l'inverse, l'atout réel de
  pnpm — son `node_modules` strict, qui fait tomber les imports transitifs non déclarés — serait
  encaissé au pire moment : au milieu de cinq migrations de majeure, il ajouterait un suspect à
  chaque panne. C'est un bon candidat **après** QUA-5, quand l'arbre est à jour et que rien d'autre
  ne bouge ; ça mériterait alors sa propre ligne de roadmap.

## 7. Conséquences sur la documentation

- **[roadmap.md](../../roadmap.md)** : QUA-5 passe à `fait`. La ligne **SEC-6** est amendée — son
  volet front (7 vulnérabilités) est clos, il ne reste que le **scan automatique** de dépendances,
  back compris. Le point 2 de l'« ordre recommandé » perd QUA-5 au profit de QUA-8 puis UX-4.
- **[CLAUDE.md](../../../CLAUDE.md)** : les deux outils de constat faussés (§ 2.1) et, s'il se
  confirme, ce que jsdom 27 change au harnais (§ 4.2).
- **[README.md](../../../README.md)** : le prérequis Node 24 pour le développement local.
