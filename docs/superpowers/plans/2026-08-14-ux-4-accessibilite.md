# UX-4 — Accessibilité : plan d'implémentation

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** rendre l'interface utilisable au clavier et lisible en thème sombre, corriger les
contrastes mesurés en défaut, et laisser un garde-fou automatique contre la régression.

**Architecture:** chantier **front seul** — aucun fichier backend n'est touché. Trois natures de
changement : des **tokens CSS** (`index.css`, plus deux surcharges locales), des **attributs
d'accessibilité** dans six composants React, et une **fonction pure** dans `color.ts` qui choisit
l'avant-plan lisible d'une pastille de ligne. Le garde-fou est `axe-core` appelé depuis les tests de
composants existants.

**Tech Stack:** React 19, TypeScript 6.0.3, Vite 8, Vitest 4 + jsdom 27 + Testing Library,
CSS Modules, `axe-core` 4.13 (nouvelle dépendance de développement).

**Spec:** [docs/superpowers/specs/2026-08-14-ux-4-accessibilite-design.md](../specs/2026-08-14-ux-4-accessibilite-design.md)

## Global Constraints

- **Branche** : `feat/ux-4-accessibilite`, déjà créée. Ne pas travailler sur `master`.
- **Ne jamais démarrer ni arrêter le backend, le front ou Docker** — l'utilisateur les gère depuis
  son IDE. Demander avant.
- **Chantier front seul** : ne toucher aucun fichier sous `backend/`. `./mvnw` n'a pas à être lancé.
- **Vérification de référence** : depuis `frontend/`, `npm test` (Vitest), `npm run build`
  (`tsc -b` + build, donc aussi la vérif de typage) et `npm run lint` — **le lint doit rester
  muet**.
- **TDD** : écrire le test qui échoue avant l'implémentation. Un test de filet écrit sur du code déjà
  correct passe du premier coup et ne prouve rien (leçon de QUA-8) : le voir rougir fait partie de la
  tâche.
- **Ne pas dégrader l'expérience utilisateur.** UX-4 change le rendu volontairement, ce qui est aussi
  le meilleur moyen de l'abîmer sans le voir. **Tout changement de teinte doit être justifié par une
  mesure**, jamais par un goût ; tout changement de rendu non demandé par la spec est un défaut.
- **Commentaires sobres** : uniquement le « pourquoi » non évident, en une à deux lignes. Ne pas
  commenter ce que le code dit déjà.
- **Messages de commit en français**, préfixés `feat(ux-4):`, `test(ux-4):`, `refactor(ux-4):` ou
  `docs(ux-4):`.
- **CSS Modules colocalisés** (`X.module.css` à côté de `X.tsx`). `index.css` est réservé à ce qui
  vaut pour **tout le document** : la garde `[hidden]`, le mapping `[data-severity]`, les tokens, et
  l'anneau de focus de la tâche 1. Une règle utilisée par un seul composant va dans son module.
- **Aucun module ne surcharge un autre à spécificité égale** : l'ordre des règles dans la feuille
  émise vient du graphe d'imports et n'est pas garanti.
- **Tout masquage passe par l'attribut `hidden`**, jamais par `display: none` — sauf le masquage
  *visuel* de la tâche 5, qui doit précisément **rester** dans l'arbre d'accessibilité (donc ni
  `hidden`, ni `display: none`).
- **Une variable CSS dans un attribut `style` exige `as CSSProperties`** : `tsc` la refuse sans
  (TS2353).
- **Un `<button>` n'hérite pas de la police du document.** Ne poser `font-family` que si l'attribut
  remplacé posait une famille ; en ajouter une là où il n'y avait qu'une taille changerait le rendu.
- **Aucun test ne voit une règle CSS** : Vitest n'applique pas les feuilles de style. Le rendu ne se
  vérifie qu'en navigateur — ce n'est pas une excuse pour ne rien tester, c'est la raison pour
  laquelle les tâches CSS n'ont pas de test et les tâches d'attributs en ont.
- **TypeScript reste en 6.0.3** : ne pas monter en 7 (`typescript-eslint` s'interrompt).
- **Ne jamais utiliser `feature-state` sur la couche `vehicles`** (sans objet ici, mais la règle
  tient).
- **Seuils de contraste** : 4,5:1 pour le texte, 3:1 pour le non-textuel (bordures, anneau de
  focus). Aucun texte de l'interface ne relève du seuil « grand texte ».

---

### Task 1: Tokens, anneau de focus et palette sombre

CSS seul. Aucun comportement ne change, aucun composant n'est modifié. Le rendu **clair** ne doit
bouger que sur deux points voulus : `--text-faint` (contraste) et la couleur de texte principale, qui
passe du noir par défaut du navigateur à `#111`.

**Files:**
- Modify: `frontend/src/index.css` (bloc `:root` lignes 20-60, règle `body` lignes 16-18, et ajouts en
  fin de fichier)
- Modify: `frontend/src/ui/Sheet.module.css` (surcharge de l'anneau sur la poignée)
- Modify: `frontend/src/ui/StopPanel.module.css` (surcharge de l'anneau sur `.isolate`)
- Test: aucun. Aucun test ne voit une règle CSS ; la vérification est `npm test` + `npm run build`
  sans régression, puis la recette navigateur.

**Interfaces:**
- Consumes: rien.
- Produces: les tokens `--text`, `--on-sev`, `--focus` et la valeur corrigée de `--text-faint`,
  consommés par les tâches 3, 4 et 5. **Noms exacts** : `--text`, `--on-sev`, `--focus`.

- [ ] **Step 1: corriger le commentaire de tête du bloc de couleurs**

Dans `frontend/src/index.css`, remplacer le commentaire qui précède `--surface` (lignes 31-33) :

```css
  /* Surfaces, texte, filets. Les nuances proches restent distinctes ; contrairement à QUA-8, qui
     interdisait tout changement de rendu, UX-4 en change volontairement — mais seulement là où une
     mesure l'exige (`--text-faint` valait 2,85:1). */
```

- [ ] **Step 2: ajouter les trois tokens et corriger `--text-faint`**

Toujours dans le bloc `:root`. Remplacer la ligne `--text-faint: #999;` par la version corrigée, et
ajouter les trois nouveaux tokens juste après `--separator` … `--border-subtle` (avant le bloc
`--accent`) :

```css
  --text-faint: #767676;       /* heure sur la poignée ; #999 valait 2,85:1 sur blanc */
```

```css
  /* Couleur de texte principale. Le projet n'en posait aucune, tout héritait du défaut du
     navigateur — donc la règle du thème sombre n'aurait rien eu à surcharger. `#111` et non `#000`
     pour s'accorder au `text-color` des libellés de station sur la carte. */
  --text: #111;

  /* Avant-plan des puces de gravité. Séparé de `--surface`, que la puce détournait comme couleur de
     texte : en thème sombre celui-ci devient foncé, ce qui retournerait le texte sur son fond. */
  --on-sev: #fff;

  /* Anneau de focus. Aucune teinte unique ne tient les deux thèmes — mesuré, #1d4ed8 vaut 6,70:1 sur
     blanc et 2,54:1 sur la surface sombre — donc il suit `--accent`, qui bascule. */
  --focus: var(--accent);
```

Enfin, compléter le commentaire de `--separator`, qui reste **volontairement** sous le seuil et doit
dire pourquoi (la spec § 11 exige que la raison vive près du code) :

```css
  --separator: #bbb;           /* point entre deux horaires ; 1,92:1 assumé, cf. ci-dessous */
```

et, sous le bloc `:root`, avant le mapping `[data-severity]` :

```css
/* `--separator` reste sous 4,5:1 à dessein : le « · » entre deux horaires est `aria-hidden` et
   purement décoratif — la séparation est portée par les boîtes des boutons —, or le critère 1.4.3
   exempte le texte décoratif. Le remonter le ferait concurrencer les horaires qu'il sépare. */
```

- [ ] **Step 3: appliquer la couleur de texte sur `body`**

Remplacer la règle `body` (lignes 16-18) :

```css
body {
  font-family: var(--font);
  color: var(--text);
}
```

- [ ] **Step 4: poser l'anneau de focus global**

À ajouter dans `index.css` juste après la règle `[hidden]`, avant la media query :

```css
/* Anneau de focus unique pour tout le document. Global et non par module : la règle ne dépend
   d'aucun composant, et `:focus-visible` étant un sélecteur d'état, elle atteint aussi les contrôles
   MapLibre (zoom, boussole, « ⓘ ») sans nommer leurs classes tierces — ce que `--tap` ne sait pas
   faire (cf. CLAUDE.md). */
:focus-visible {
  outline: 2px solid var(--focus);
  outline-offset: 2px;
}
```

- [ ] **Step 5: ajouter la palette sombre**

À la fin d'`index.css`, après la media query `max-width: 720px` :

```css
/* Thème sombre des panneaux seuls : la carte reste claire (le style tiers ne bascule pas, cf. UX-6).
   Pas d'interrupteur manuel — le projet n'a pas d'écran de réglages, et le système exprime déjà la
   préférence.

   `--sev-perturbee` et `--sev-information` ne sont PAS redéfinis : ce sont des alias de `--warn` et
   `--accent`, résolus à l'usage, donc ils suivent gratuitement. Les deux puces d'état de StopPanel
   (`--amber-*`, `--red-*`) restent claires à dessein : mesurées à 13,68:1 et 11,78:1 contre le
   panneau sombre, les inverser serait du travail pour rien. */
@media (prefers-color-scheme: dark) {
  :root {
    --surface: #1b1c1f;
    --surface-off: #2a2b2f;

    --text: #e8e8ea;             /* 13,92:1 */
    --text-detail: #c6c8cc;      /* 10,17:1 */
    --text-detail-open: #bfc1c6;
    --text-muted: #a5a7ac;       /* 7,08:1 */
    --text-faint: #8f9196;       /* 5,40:1 */

    --separator: #5a5c61;        /* assombri, pas éclairci : #bbb serait trop voyant sur ce fond */
    --handle: #4a4c51;
    --border: #3a3c41;
    --border-subtle: #2e3034;

    --accent: #8ab0ff;           /* 7,90:1 ; #1d4ed8 tombait à 2,54:1 */
    --warn: #f0a559;             /* 8,30:1 ; #b45309 tombait à 3,39:1 */

    /* Éclaircies pour tenir leur rôle de bordure (≥ 3:1 sur la surface), ce que #b91c1c ne faisait
       plus (2,63:1). Le texte de la puce suit par `--on-sev`, qui bascule en même temps. */
    --sev-bloquante: #ef8f8f;
    --sev-inconnue: #a1a5ad;

    --on-sev: #17181a;

    /* L'ombre portée ne se lit plus sur du sombre, alors qu'elle est le seul signe qui détache un
       panneau de la carte restée claire : un filet la remplace, l'ombre restant pour la profondeur. */
    --shadow-card: 0 0 0 1px var(--border), 0 2px 12px rgba(0, 0, 0, .5);
    --shadow-sheet: 0 0 0 1px var(--border), 0 -2px 16px rgba(0, 0, 0, .5);
  }
}
```

- [ ] **Step 6: surcharger l'anneau sur la poignée de la feuille**

Dans `frontend/src/ui/Sheet.module.css`, après la règle `.grip` :

```css
/* La poignée fait 44 px sur toute la largeur pour un grip visible de 36×4 : l'anneau global s'y
   lirait « la feuille est focalisée » et non « ce bouton l'est ». On le reporte sur le grip. */
.handle:focus-visible {
  outline: none;
}

.handle:focus-visible .grip {
  outline: 2px solid var(--focus);
  outline-offset: 4px;
}
```

- [ ] **Step 7: arrondir l'anneau sur le bouton d'isolement**

Dans `frontend/src/ui/StopPanel.module.css`, après la règle `.isolate` :

```css
/* La cible tactile monte à 44 px sous 720 px pour une pastille ronde de 18 px : l'anneau
   rectangulaire de la règle globale encadrerait du vide et ferait croire à un bouton carré. */
.isolate:focus-visible {
  border-radius: 999px;
}
```

- [ ] **Step 8: vérifier que rien n'a régressé**

Depuis `frontend/` :

```bash
npm test && npm run build && npm run lint
```

Attendu : les 93 tests passent, `tsc -b` et le build sortent sans erreur, ESLint reste muet. Aucun
test ne peut constater l'effet de cette tâche — c'est attendu et documenté.

- [ ] **Step 9: commit**

```bash
git add frontend/src/index.css frontend/src/ui/Sheet.module.css frontend/src/ui/StopPanel.module.css
git commit -m "feat(ux-4): anneau de focus, couleur de texte et palette sombre des panneaux"
```

---

### Task 2: Harnais `axe-core`

Brancher le filet **avant** les correctifs, pour que sa ligne de base soit mesurée et non
reconstituée après coup. Attention : ce filet **ne rougira probablement pas** sur les défauts des
tâches 4 et 5 — c'est prévu et expliqué dans la spec § 9. Sa valeur est en aval.

**Files:**
- Modify: `frontend/package.json` (dépendance de développement)
- Create: `frontend/src/test/axe.ts`
- Modify: les dix fichiers de test de composants — `frontend/src/ui/DisruptionRow.test.tsx`,
  `LinePicker.test.tsx`, `NetworkStatus.test.tsx`, `NetworkSummary.test.tsx`, `PanelHeader.test.tsx`,
  `Sheet.test.tsx`, `SheetFooter.test.tsx`, `StaleWarning.test.tsx`, `StopPanel.test.tsx`,
  `VehiclePanel.test.tsx`

**Interfaces:**
- Consumes: rien.
- Produces: `expectNoA11yViolations(container: Element): Promise<void>`, exportée par
  `src/test/axe.ts`. Nom anglais comme les deux autres helpers du projet (`triggerResize`,
  `stubHeight`). Consommée par les tâches 4 et 5.

- [ ] **Step 1: installer `axe-core`**

Depuis `frontend/` :

```bash
npm install --save-dev axe-core@^4.13.0
```

**Pas de wrapper** : `axe-core` n'a aucune dépendance, là où `jest-axe` embarquerait `chalk` 4,
`lodash.merge` et `jest-matcher-utils` 30 (un paquet Jest dans un projet Vitest) tout en épinglant
une mineure d'`axe-core` en retard ; `vitest-axe` est resté en 0.1.0 depuis janvier 2025.

- [ ] **Step 2: écrire le helper**

Créer `frontend/src/test/axe.ts` :

```ts
import axe from "axe-core";
import { expect } from "vitest";

/**
 * Règles de niveau page, désactivées : un composant monté seul dans un `div` n'a ni `main`, ni `h1`,
 * ni `lang`, donc elles rougiraient partout sans rien dire du composant. Ce que ça coûte est assumé
 * (spec § 9) : `h1`, `main` et régions nommées ne sont couverts que par les assertions écrites à la
 * main de la tâche 5 et par la recette — aucun test ne monte `App`, qui construit MapLibre.
 */
const PAGE_LEVEL_RULES = [
  "region",
  "landmark-one-main",
  "page-has-heading-one",
  "html-has-lang",
  "html-lang-valid",
  "document-title",
  "bypass",
];

/**
 * Échoue si axe relève une violation dans le fragment rendu, en nommant la règle et le sélecteur.
 *
 * Ne voit PAS le contraste : jsdom n'applique aucune feuille de style, donc `color-contrast` sort en
 * « incomplete » et non en violation. Tout le contraste reste à la recette navigateur.
 *
 * `document.body` par défaut : chaque test ne monte qu'un composant, donc le corps EST le fragment —
 * et le `renderSheet` de `Sheet.test.tsx` ne rend pas son conteneur.
 */
export async function expectNoA11yViolations(container: Element = document.body) {
  const results = await axe.run(container, {
    rules: Object.fromEntries(PAGE_LEVEL_RULES.map((id) => [id, { enabled: false }])),
  });
  const details = results.violations.map((violation) =>
    `${violation.id} (${violation.impact}) : ${violation.help}\n`
    + violation.nodes.map((node) => `      ${node.target.join(" ")}`).join("\n"),
  );
  expect(details, `Violations d'accessibilité :\n    ${details.join("\n    ")}`).toEqual([]);
}
```

- [ ] **Step 3: brancher le helper sur un premier fichier et lire son verdict**

Dans `frontend/src/ui/LinePicker.test.tsx`, ajouter l'import et un cas à la fin du `describe` :

```ts
import { expectNoA11yViolations } from "../test/axe";
```

```ts
  it("ne présente aucune violation détectable par axe", async () => {
    renderPicker({
      disruptions: new Map([[LIGNE_8.id, perturbation("BLOQUANTE", "Métro 8 : Trafic interrompu")]]),
      disrupted: [LIGNE_8],
      disruptionsOpen: true,
      visible: new Set([LIGNE_9.id]),
    });

    await expectNoA11yViolations();
  });
```

- [ ] **Step 4: exécuter et CONSIGNER le verdict réel**

```bash
npm test -- LinePicker
```

**Les deux résultats sont acceptables** et aucun ne doit être « corrigé » pour ressembler à
l'attendu :

- **Vert** : c'est la ligne de base. axe ne peut pas savoir qu'une pastille est une bascule, et son
  contenu textuel (« 912 ») suffit à la règle `button-name` malgré un nom accessible inutilisable.
  Noter dans le rapport de tâche « axe vert sur la ligne de base, comme prévu spec § 9 ».
- **Rouge** : lire la violation et la **reporter telle quelle** dans le rapport de tâche. Ne pas la
  corriger ici — les correctifs sont les tâches 3 à 5. Si la violation ne relève d'aucune de ces
  tâches, la signaler comme trouvaille.

- [ ] **Step 5: brancher les neuf autres fichiers**

Dans chacun, ajouter `import { expectNoA11yViolations } from "../test/axe";` et le cas ci-dessous en
fin de `describe`. Les helpers et fixtures cités (`item`, `renderSummary`, `departures`, `vehicle`,
`renderSheet`) **existent déjà** dans leur fichier : les réutiliser, ne rien créer.

`DisruptionRow.test.tsx` — la variante dépliée, qui montre puce, titre-bouton et détail :

```tsx
  it("ne présente aucune violation détectable par axe", async () => {
    render(<ul><DisruptionRow item={item({ detail: "Un train stationne à Bobigny." })} /></ul>);
    fireEvent.click(screen.getByRole("button"));

    await expectNoA11yViolations();
  });
```

`NetworkStatus.test.tsx` :

```tsx
  it("ne présente aucune violation détectable par axe", async () => {
    render(<NetworkStatus status="error" />);

    await expectNoA11yViolations();
  });
```

`NetworkSummary.test.tsx` — la variante qui expose les trois boutons à la fois :

```tsx
  it("ne présente aucune violation détectable par axe", async () => {
    renderSummary({ disruptedCount: 3, canShowAll: true, collapsible: true, expanded: true });

    await expectNoA11yViolations();
  });
```

`PanelHeader.test.tsx` :

```tsx
  it("ne présente aucune violation détectable par axe", async () => {
    render(<PanelHeader title="République" onClose={vi.fn()} />);

    await expectNoA11yViolations();
  });
```

`Sheet.test.tsx` — à un cran **ouvert** : au cran `apercu`, corps, résumé et pied sont `hidden`, donc
axe ne verrait presque rien.

```tsx
  it("ne présente aucune violation détectable par axe", async () => {
    renderSheet("moitie", "2026-08-11T14:32:10Z");

    await expectNoA11yViolations();
  });
```

`SheetFooter.test.tsx` :

```tsx
  it("ne présente aucune violation détectable par axe", async () => {
    render(<SheetFooter asOf="2026-08-11T14:32:10Z" />);

    await expectNoA11yViolations();
  });
```

`StaleWarning.test.tsx` :

```tsx
  it("ne présente aucune violation détectable par axe", async () => {
    render(<StaleWarning stale />);

    await expectNoA11yViolations();
  });
```

`StopPanel.test.tsx` — avec une perturbation, pour couvrir aussi la `DisruptionRow` imbriquée :

```tsx
  it("ne présente aucune violation détectable par axe", async () => {
    render(<StopPanel data={departures({
      disruptions: [{
        severity: "BLOQUANTE", cause: "PERTURBATION", title: "Métro 3 : Trafic interrompu",
        shortMessage: "Trafic interrompu", detail: "",
      }],
    })} onSelectTrain={vi.fn()} onSelectLine={vi.fn()} />);

    await expectNoA11yViolations();
  });
```

`VehiclePanel.test.tsx` — suivi actif, pour couvrir `aria-pressed` :

```tsx
  it("ne présente aucune violation détectable par axe", async () => {
    render(<VehiclePanel vehicle={vehicle({ confidence: "APPROXIMATE" })} following onFollow={vi.fn()} />);

    await expectNoA11yViolations();
  });
```

Adapter aussi le cas de l'étape 3 (`LinePicker.test.tsx`) pour qu'il appelle
`expectNoA11yViolations()` sans argument, plutôt que de destructurer `container`.

- [ ] **Step 6: exécuter la suite complète**

```bash
npm test && npm run lint
```

Attendu : 103 tests (93 + 10), lint muet. Consigner tout verdict rouge dans le rapport de tâche sans
le corriger.

- [ ] **Step 7: commit**

```bash
git add frontend/package.json frontend/package-lock.json frontend/src/test/axe.ts frontend/src/ui/*.test.tsx
git commit -m "test(ux-4): filet axe-core sur les dix tests de composants"
```

---

### Task 3: Avant-plan calculé des pastilles de ligne

Le défaut le plus lourd du chantier : `LineBadge` peint son texte en blanc sur la teinte officielle
de la ligne, et six des huit teintes réelles échouent le seuil de 4,5:1 — jusqu'à **1,62:1** sur la
ligne 9 (`#D2D200`).

**Files:**
- Modify: `frontend/src/ui/color.ts`
- Create: `frontend/src/ui/color.test.ts`
- Modify: `frontend/src/ui/LineBadge.tsx`
- Modify: `frontend/src/ui/shared.module.css` (règle `.lineBadge`)

**Interfaces:**
- Consumes: rien de la tâche 1 (la fonction ne dépend d'aucun token — elle ne connaît que la couleur
  de ligne, ce qui la rend juste dans les deux thèmes).
- Produces: `readableOn(background: string): string` exportée par `ui/color.ts`, rendant `"#ffffff"`
  ou `"#111111"`. `LineBadge` pose désormais **deux** variables CSS dans son `style` :
  `--line-color` et `--line-fg`.

- [ ] **Step 1: écrire le test de caractérisation de `lightenForTrack`**

`lightenForTrack` n'a **aucun test** et la tâche va extraire une fonction de son corps. On épingle
d'abord son comportement actuel, sinon le refactor n'a pas de filet.

Créer `frontend/src/ui/color.test.ts` :

```ts
import { describe, expect, it } from "vitest";
import { lightenForTrack } from "./color";

describe("lightenForTrack", () => {
  it("éclaircit vers le blanc en gardant 45 % de la teinte", () => {
    expect(lightenForTrack("#D2D200")).toBe("rgb(235, 235, 140)");
    expect(lightenForTrack("#640082")).toBe("rgb(185, 140, 199)");
  });

  it("accepte la forme courte à trois chiffres", () => {
    expect(lightenForTrack("#f00")).toBe("rgb(255, 140, 140)");
  });

  it("traite une composante illisible comme un zéro", () => {
    // Le tracé doit rester dessiné même si le flux sert une couleur cassée.
    expect(lightenForTrack("#zzzzzz")).toBe("rgb(140, 140, 140)");
  });
});
```

- [ ] **Step 2: exécuter — doit passer du premier coup**

```bash
npm test -- color
```

Attendu : **PASS**. C'est un test de caractérisation, pas un test de régression : il décrit ce qui
existe. S'il échoue, l'attendu écrit ci-dessus est faux — recalculer plutôt que de modifier
`lightenForTrack`.

- [ ] **Step 3: écrire le test de `readableOn`, qui doit échouer**

Ajouter dans `frontend/src/ui/color.test.ts` :

```ts
import { lightenForTrack, readableOn } from "./color";
```

```ts
describe("readableOn", () => {
  it("choisit le quasi-noir sur les teintes claires du réseau", () => {
    // Le blanc y tombait entre 1,62:1 et 2,31:1 : c'est le défaut que ce chantier corrige.
    expect(readableOn("#D2D200")).toBe("#111111"); // 9
    expect(readableOn("#82DC73")).toBe("#111111"); // 6 et 7bis
    expect(readableOn("#82C8E6")).toBe("#111111"); // 13 et 3bis
    expect(readableOn("#CEADD2")).toBe("#111111"); // 8
    expect(readableOn("#FF82B4")).toBe("#111111"); // 7
  });

  it("garde le blanc sur les teintes sombres du réseau", () => {
    expect(readableOn("#640082")).toBe("#ffffff"); // 14
  });

  it("tranche par la mesure et non par un seuil de luminance", () => {
    // La ligne 3 est le cas limite : luminance basse, et pourtant le blanc contraste mieux
    // (5,39:1 contre 3,50:1). Un seuil de luminance se tromperait ici.
    expect(readableOn("#6E6E00")).toBe("#ffffff");
  });

  it("reste lisible sur les extrêmes et sur une couleur cassée", () => {
    expect(readableOn("#000000")).toBe("#ffffff");
    expect(readableOn("#ffffff")).toBe("#111111");
    // Composantes illisibles traitées comme du noir, comme dans lightenForTrack.
    expect(readableOn("#zzzzzz")).toBe("#ffffff");
  });
});
```

- [ ] **Step 4: exécuter — doit échouer**

```bash
npm test -- color
```

Attendu : **FAIL**, `readableOn is not a function` (ou une erreur d'import TypeScript).

- [ ] **Step 5: implémenter dans `color.ts`**

Remplacer le contenu de `frontend/src/ui/color.ts` par :

```ts
const WHITE = "#ffffff";
/** Pas `#000` : `#111` est déjà la couleur de texte du projet (`--text`, libellés de station). */
const NEAR_BLACK = "#111111";

/** Composantes 0-255 d'un `#rgb` ou `#rrggbb`. Une composante illisible vaut zéro : le flux sert la
 *  couleur, et un tracé doit rester dessiné même si elle est cassée. */
function channels(hex: string): [number, number, number] {
  const value = hex.replace("#", "");
  const full = value.length === 3 ? value.split("").map((c) => c + c).join("") : value;
  const at = (offset: number) => {
    const raw = Number.parseInt(full.slice(offset, offset + 2), 16);
    return Number.isNaN(raw) ? 0 : raw;
  };
  return [at(0), at(2), at(4)];
}

/** Luminance relative WCAG 2.x. */
function luminance(hex: string): number {
  const [r, g, b] = channels(hex).map((value) => {
    const channel = value / 255;
    return channel <= 0.03928 ? channel / 12.92 : ((channel + 0.055) / 1.055) ** 2.4;
  });
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}

function contrast(a: string, b: string): number {
  const first = luminance(a);
  const second = luminance(b);
  return (Math.max(first, second) + 0.05) / (Math.min(first, second) + 0.05);
}

/**
 * Éclaircit une couleur de ligne pour le tracé de fond.
 *
 * Reproduit le rendu de l'ancien `line-opacity: 0.45` sur fond clair, mais de façon
 * **idempotente sous superposition** : deux branches d'une même ligne partagent leur tronc
 * (~15 km sur 21 pour la 7), donc celui-ci est dessiné deux fois exactement superposé. Avec
 * une opacité de 0,45, l'opacité résultante monterait à ~0,70 et le tronc commun de la 7, de
 * la 13 et de la 10 apparaîtrait visiblement plus foncé que le reste du réseau.
 */
export function lightenForTrack(hex: string, keep = 0.45): string {
  const [r, g, b] = channels(hex).map((base) => Math.round(base * keep + 255 * (1 - keep)));
  return `rgb(${r}, ${g}, ${b})`;
}

/**
 * Avant-plan lisible sur une couleur de ligne : blanc ou quasi-noir, celui des deux qui contraste le
 * mieux.
 *
 * Les teintes officielles ne sont **pas** dessinées pour du blanc, contrairement à ce que faisait
 * `LineBadge` : mesuré sur les valeurs réelles du flux, six sur huit échouent le seuil de 4,5:1 avec
 * du blanc, jusqu'à 1,62:1 sur la ligne 9. La signalétique RATP fait l'inverse — le 9 est noir sur
 * jaune.
 *
 * Le choix se **calcule** au lieu de suivre un seuil de luminance, qui se tromperait sur les teintes
 * moyennes : la ligne 3 (`#6E6E00`) a une luminance basse et demande pourtant du blanc.
 */
export function readableOn(background: string): string {
  return contrast(WHITE, background) >= contrast(NEAR_BLACK, background) ? WHITE : NEAR_BLACK;
}
```

- [ ] **Step 6: exécuter — tout doit passer**

```bash
npm test -- color
```

Attendu : **PASS** sur les deux `describe`. Le test de caractérisation de l'étape 1 prouve que
l'extraction de `channels` n'a rien changé à `lightenForTrack`.

- [ ] **Step 7: écrire le test de `LineBadge`, qui doit échouer**

`LineBadge` n'a aucun fichier de test. Créer `frontend/src/ui/LineBadge.test.tsx` :

```tsx
// @vitest-environment jsdom
import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { LineBadge } from "./LineBadge";
import { expectNoA11yViolations } from "../test/axe";

afterEach(cleanup);

describe("LineBadge", () => {
  it("pose l'avant-plan lisible avec la teinte, pas seulement la teinte", () => {
    // Sur le jaune de la ligne 9, le blanc tombait à 1,62:1.
    render(<LineBadge color="#D2D200" shortName="9" size="s" />);

    const badge = screen.getByText("9");
    expect(badge.style.getPropertyValue("--line-color")).toBe("#D2D200");
    expect(badge.style.getPropertyValue("--line-fg")).toBe("#111111");
  });

  it("garde le blanc sur une teinte sombre", () => {
    render(<LineBadge color="#640082" shortName="14" size="m" />);

    expect(screen.getByText("14").style.getPropertyValue("--line-fg")).toBe("#ffffff");
  });

  it("ne présente aucune violation détectable par axe", async () => {
    render(<LineBadge color="#D2D200" shortName="9" size="s" />);

    await expectNoA11yViolations();
  });
});
```

- [ ] **Step 8: exécuter — doit échouer**

```bash
npm test -- LineBadge
```

Attendu : **FAIL** sur les deux premiers cas, `--line-fg` valant `""` (le composant ne pose que
`--line-color`).

- [ ] **Step 9: implémenter dans `LineBadge`**

Remplacer le corps de `frontend/src/ui/LineBadge.tsx` (garder l'en-tête de documentation existant, en
y ajoutant la phrase sur l'avant-plan) :

```tsx
import type { CSSProperties } from "react";
import { readableOn } from "./color";
import shared from "./shared.module.css";
```

```tsx
export function LineBadge({ color, shortName, size }: Props) {
  return (
    <span
      className={shared.lineBadge}
      data-size={size}
      style={{ "--line-color": color, "--line-fg": readableOn(color) } as CSSProperties}
    >
      {shortName}
    </span>
  );
}
```

Et compléter le commentaire de tête du composant par :

```
 * L'avant-plan est **calculé** et non fixé au blanc : six des huit teintes réelles du flux échouent
 * le seuil de 4,5:1 avec du blanc (cf. `readableOn`).
```

- [ ] **Step 10: consommer la variable dans la feuille partagée**

Dans `frontend/src/ui/shared.module.css`, remplacer la ligne `color: var(--surface);` de `.lineBadge`
par :

```css
  color: var(--line-fg);
```

Et compléter le commentaire de la règle :

```css
/* Pastille ronde d'une ligne. La teinte arrive par `--line-color` (16 couleurs GTFS, ensemble non
   borné, donc impossible à figer en classes) et son avant-plan par `--line-fg`, calculé par
   `readableOn` — il ne peut pas être un token de thème, puisqu'il dépend de la teinte reçue et non
   de la surface. La taille vient de `data-size`, dans ses deux seules valeurs en usage.
   `flex: 0 0 auto` est nécessaire quand la pastille sert de `leading` à une perturbation, et inerte
   dans les deux autres emplois (elle n'y a rien qui la comprime). */
```

- [ ] **Step 11: exécuter la suite complète**

```bash
npm test && npm run build && npm run lint
```

Attendu : **113 tests** — 93 avant le chantier, +10 en tâche 2, +7 dans `color.test.ts` (3 de
caractérisation, 4 pour `readableOn`) et +3 dans `LineBadge.test.tsx`. Recompter à l'exécution ; si le
total diffère, dire lequel et pourquoi plutôt que d'ajuster le chiffre en silence. Typage et lint
verts.

- [ ] **Step 12: commit**

```bash
git add frontend/src/ui/color.ts frontend/src/ui/color.test.ts frontend/src/ui/LineBadge.tsx frontend/src/ui/LineBadge.test.tsx frontend/src/ui/shared.module.css
git commit -m "feat(ux-4): avant-plan calculé des pastilles de ligne, le blanc échouait 6 teintes sur 8"
```

---

### Task 4: État et nom accessibles des pastilles du sélecteur

Les 16 pastilles sont des bascules sans état accessible, avec un nom illisible (« 912 »), et leur
`opacity: .45` fait tomber le compteur à 3,49:1 tout en atténuant son propre anneau de focus.

**Files:**
- Modify: `frontend/src/ui/LinePicker.tsx`
- Modify: `frontend/src/ui/LinePicker.module.css`
- Modify: `frontend/src/ui/LinePicker.test.tsx`

**Interfaces:**
- Consumes: `--surface-off` et `--text-muted` (tâche 1, déjà existants, valeurs sombres ajoutées) ;
  `expectNoA11yViolations` (tâche 2).
- Produces: rien pour les tâches suivantes.

- [ ] **Step 1: écrire les tests, qui doivent échouer**

Dans `frontend/src/ui/LinePicker.test.tsx`, ajouter dans le `describe` :

```ts
  it("expose l'état affiché/masqué de chaque ligne", () => {
    // `data-shown` ne disait l'état qu'au CSS : au lecteur d'écran, une ligne masquée était
    // indiscernable d'une ligne affichée.
    renderPicker({ visible: new Set([LIGNE_9.id]) });

    expect(screen.getByTitle(/ligne 9/).getAttribute("aria-pressed")).toBe("true");
    expect(screen.getByTitle(/ligne 8/).getAttribute("aria-pressed")).toBe("false");
  });

  it("nomme la pastille au lieu de laisser lire « 912 »", () => {
    // Le `title` n'est qu'un dernier recours dans le calcul du nom accessible : le contenu
    // textuel l'emportait, donc la pastille s'annonçait « 912 ».
    renderPicker();

    expect(screen.getByRole("button", { name: "Ligne 9, 12 trains" })).not.toBeNull();
    expect(screen.getByRole("button", { name: "Ligne 8, 7 trains" })).not.toBeNull();
  });

  it("annonce la gravité dans le nom, sans y déverser les titres", () => {
    renderPicker({
      disruptions: new Map([[LIGNE_8.id, perturbation("BLOQUANTE", "Métro 8 : Trafic interrompu")]]),
    });

    const pastille = screen.getByRole("button", { name: "Ligne 8, 7 trains, trafic bloqué" });
    // Le détail reste dans l'infobulle, qui a la place de le porter.
    expect(pastille.getAttribute("title")).toContain("Métro 8 : Trafic interrompu");
  });

  it("accorde le nom au singulier", () => {
    renderPicker({ counts: new Map([[LIGNE_9.id, 1], [LIGNE_8.id, 0]]) });

    expect(screen.getByRole("button", { name: "Ligne 9, 1 train" })).not.toBeNull();
    expect(screen.getByRole("button", { name: "Ligne 8, 0 train" })).not.toBeNull();
  });
```

- [ ] **Step 2: exécuter — doit échouer**

```bash
npm test -- LinePicker
```

Attendu : **FAIL** sur les quatre cas — `aria-pressed` absent, et les noms accessibles valant
« 912 » / « 87 ».

- [ ] **Step 3: corriger le commentaire devenu faux dans le fichier de test**

Le commentaire des lignes 39-41 affirme « ce bouton n'a pas d'`aria-label`, donc son nom accessible
est son contenu textuel (« 912 ») ». Ce n'est plus vrai. Le remplacer par :

```ts
    // Deux boutons, un par ligne suivie ; le shortName vit dans la pastille. On lit ici le contenu
    // textuel, pas le nom accessible : celui-ci vient désormais d'un `aria-label` (cas suivants).
```

- [ ] **Step 4: implémenter dans `LinePicker.tsx`**

Remplacer le corps de la fonction de rendu des pastilles :

```tsx
      <div className={styles.pills}>
        {lines.map((line) => {
          const shown = !visible || visible.has(line.id);
          const disruption = disruptions.get(line.id);
          const severity = disruption ? severityMeta(disruption.severity) : null;
          const count = counts.get(line.id) ?? 0;
          // Le nom porte l'identité, le décompte et la gravité ; l'état vient d'`aria-pressed`, le
          // redire ici le ferait annoncer deux fois. Le détail des perturbations reste dans le
          // `title`, qui a la place de le porter.
          const label = `Ligne ${line.shortName}, ${count} train${count > 1 ? "s" : ""}`
            + (severity ? `, ${severity.label}` : "");
          return (
            <button
              key={line.id}
              onClick={() => onToggle(line.id)}
              title={disruption
                ? `Ligne ${line.shortName} — ${severity!.label} : ${disruption.items.map((i) => i.title).join(" · ")}`
                : `${count} train(s) sur la ligne ${line.shortName}`}
              aria-label={label}
              aria-pressed={shown}
              className={styles.pill}
              data-severity={disruption?.severity}
            >
              <LineBadge color={line.color} shortName={line.shortName} size="s" />
              {count}
              {severity && <span className={styles.glyph}>{severity.glyph}</span>}
            </button>
          );
        })}
      </div>
```

Noter les deux suppressions volontaires : `data-shown` disparaît — `aria-pressed` porte le même état
et c'est lui qui compte —, et les deux `counts.get(line.id) ?? 0` dupliqués deviennent la constante
`count`.

- [ ] **Step 5: styler l'état masqué depuis `aria-pressed`**

Dans `frontend/src/ui/LinePicker.module.css`, remplacer la règle `.pill[data-shown="false"]` :

```css
/* Ligne exclue du filtre courant : atténuée, jamais retirée — on doit pouvoir la rallumer. Piloté
   par `aria-pressed` et non par un `data-*` parallèle : un seul état, et c'est l'état accessible
   (même motif que `.follow` dans VehiclePanel.module.css).
   Sans `opacity`, contrairement à ce qui était écrit ici : à .45, le compteur tombait à 3,49:1 et
   l'anneau de focus s'atténuait avec lui, l'opacité s'appliquant à l'élément entier, outline
   comprise. Deux tokens explicites disent la même chose à 5,17:1. */
.pill[aria-pressed="false"] {
  background: var(--surface-off);
  color: var(--text-muted);
}
```

- [ ] **Step 6: exécuter — tout doit passer**

```bash
npm test -- LinePicker
```

Attendu : **PASS** sur l'ensemble du fichier, y compris les quatre cas préexistants et le cas axe de
la tâche 2.

- [ ] **Step 7: exécuter la suite complète**

```bash
npm test && npm run build && npm run lint
```

- [ ] **Step 8: commit**

```bash
git add frontend/src/ui/LinePicker.tsx frontend/src/ui/LinePicker.module.css frontend/src/ui/LinePicker.test.tsx
git commit -m "feat(ux-4): les pastilles de ligne annoncent leur état et portent un nom lisible"
```

---

### Task 5: Structure du document et avant-plan des puces de gravité

Un lecteur d'écran n'a aujourd'hui aucun plan : pas de `h1`, un `h3` orphelin, `FloatingCard` en
`div` anonyme.

**Files:**
- Modify: `frontend/src/App.tsx`
- Modify: `frontend/src/App.module.css`
- Modify: `frontend/src/ui/PanelHeader.tsx`
- Modify: `frontend/src/ui/PanelHeader.test.tsx`
- Modify: `frontend/src/ui/FloatingCard.tsx`
- Create: `frontend/src/ui/FloatingCard.test.tsx`
- Modify: `frontend/src/ui/DisruptionRow.module.css`

**Interfaces:**
- Consumes: `--on-sev` (tâche 1) ; `expectNoA11yViolations` (tâche 2).
- Produces: `FloatingCard` gagne une prop **obligatoire** `label: string`, placée après `anchor` dans
  l'interface `Props`. Les deux appels d'`App.tsx` doivent la fournir.

- [ ] **Step 1: écrire le test de `PanelHeader`, qui doit échouer**

Dans `frontend/src/ui/PanelHeader.test.tsx`, remplacer le premier cas :

```tsx
  it("porte le titre en en-tête de niveau 2, sous le h1 de la page", () => {
    render(<PanelHeader title="République" onClose={vi.fn()} />);

    expect(screen.getByRole("heading", { level: 2 }).textContent).toBe("République");
  });
```

- [ ] **Step 2: écrire le test de `FloatingCard`, qui doit échouer**

Créer `frontend/src/ui/FloatingCard.test.tsx` :

```tsx
// @vitest-environment jsdom
import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { FloatingCard } from "./FloatingCard";
import { expectNoA11yViolations } from "../test/axe";

afterEach(cleanup);

describe("FloatingCard", () => {
  it("est une région nommée, comme la feuille du rendu étroit", () => {
    // Un `div` anonyme n'apparaît pas dans le plan d'un lecteur d'écran.
    render(<FloatingCard anchor="bottom-left" label="État du réseau">contenu</FloatingCard>);

    expect(screen.getByRole("region", { name: "État du réseau" })).not.toBeNull();
  });

  it("garde la classe du parent en plus de la sienne", () => {
    // Régression possible du passage de `div` à `section` : la composition de classes.
    const { container } = render(
      <FloatingCard anchor="top-right" label="Détail" className="ficheStation">x</FloatingCard>,
    );

    expect(container.querySelector("section")!.className).toContain("ficheStation");
  });

  it("ne présente aucune violation détectable par axe", async () => {
    render(<FloatingCard anchor="top-right" label="Détail">contenu</FloatingCard>);

    await expectNoA11yViolations();
  });
});
```

- [ ] **Step 3: exécuter — doit échouer**

```bash
npm test -- PanelHeader FloatingCard
```

Attendu : **FAIL** — `PanelHeader` rend un `h3`, et `FloatingCard` n'accepte pas `label` (erreur de
typage) ni ne rend de `region`.

- [ ] **Step 4: passer `PanelHeader` en `h2`**

Dans `frontend/src/ui/PanelHeader.tsx`, remplacer le `<h3>` par un `<h2>` et adapter le commentaire :

```tsx
      {/* Aucun style de police : le `<h3>` d'origine n'en avait pas non plus et héritait du
          défaut du navigateur. Le passage en `h2` change donc la taille rendue — c'est voulu, il
          n'y avait aucun `h1` au-dessus et le niveau 3 était orphelin. */}
      <h2 className={styles.title}>{title}</h2>
```

**Le rendu doit être préservé, et il ne l'est pas tout seul.** Vérifié : `.title` de
`PanelHeader.module.css` ne pose **aucune** `font-size`, donc le titre héritait du 1.17em par défaut
du `h3` et passerait à 1.5em en `h2` — un titre nettement plus gros dans les deux mises en page.
Poser donc la taille d'origine dans `.title` :

```css
/* Le titre héritait du 1.17em par défaut d'un `<h3>`. Passé en `h2` pour la structure du document
   (UX-4), il faut reposer cette taille : le niveau de titre est un geste sémantique, pas visuel. */
.title {
  margin: 0;
  flex: 1;
  min-width: 0;
  font-size: 1.17em;
}
```

Et remplacer le commentaire de tête de la règle, qui dit aujourd'hui l'inverse (« Aucun style de
police sur le titre […] En fixer un le rétrécirait »).

- [ ] **Step 5: nommer `FloatingCard`**

Remplacer `frontend/src/ui/FloatingCard.tsx` :

```tsx
import type { ReactNode } from "react";
import styles from "./FloatingCard.module.css";

type Anchor = "top-right" | "bottom-left";

interface Props {
  anchor: Anchor;
  /** Nom de la région, lu par les technologies d'assistance. Obligatoire : une `<section>` sans nom
   *  n'est pas une région et n'apparaît dans aucun plan de document. */
  label: string;
  /** Ce que ce panneau-là fait différemment (padding, police, largeur), fourni par le parent. */
  className?: string;
  children: ReactNode;
}

/**
 * Carte flottante du rendu large. Existe pour que les panneaux ignorent où ils sont posés : sur
 * écran étroit c'est `Sheet` qui les accueille, sans qu'ils aient à le savoir — et comme `Sheet`,
 * c'est une `<section>` nommée, pour que les deux mises en page se présentent de la même façon.
 */
export function FloatingCard({ anchor, label, className, children }: Props) {
  return (
    <section
      aria-label={label}
      className={className ? `${styles.card} ${className}` : styles.card}
      data-anchor={anchor}
    >
      {children}
    </section>
  );
}
```

- [ ] **Step 6: fournir les deux libellés dans `App.tsx`**

Dans le rendu large (lignes ~369-380), ajouter `label` aux deux appels, en reprenant les mêmes
termes que la prop `label` de `Sheet` juste au-dessus, pour que les deux mises en page se nomment
pareil :

```tsx
          {ficheHeader && (
            <FloatingCard
              anchor="top-right"
              label="Détail"
              className={station ? styles.ficheStation : styles.ficheTrain}
            >
              {ficheHeader}
              {ficheBody}
            </FloatingCard>
          )}
          <FloatingCard anchor="bottom-left" label="État du réseau" className={styles.reseau}>
```

- [ ] **Step 7: ajouter le `h1` visuellement masqué**

Dans `frontend/src/App.module.css`, ajouter :

```css
/* Masquage visuel qui conserve l'élément dans l'arbre d'accessibilité — ni `hidden` ni
   `display: none`, qui l'en retireraient. Colocalisé ici plutôt que dans `index.css` : un seul
   consommateur, donc rien de document-wide. */
.srOnly {
  position: absolute;
  width: 1px;
  height: 1px;
  margin: -1px;
  padding: 0;
  overflow: hidden;
  clip-path: inset(50%);
  white-space: nowrap;
  border: 0;
}
```

Dans `frontend/src/App.tsx`, premier enfant du fragment rendu :

```tsx
  return (
    <>
      {/* Un plan de document commence par un titre de niveau 1. Masqué visuellement : le titre
          existe déjà dans `<title>` et l'écran est tout entier occupé par la carte. */}
      <h1 className={styles.srOnly}>MapIDF — métro d'Île-de-France</h1>
      <div ref={container} className={styles.map} />
```

- [ ] **Step 8: faire consommer `--on-sev` par la puce de gravité**

Dans `frontend/src/ui/DisruptionRow.module.css`, règle `.badge`, remplacer
`color: var(--surface);` par :

```css
  color: var(--on-sev);
```

Et compléter le commentaire de la règle :

```css
/* La teinte descend de `[data-severity]`, posé sur la rangée (cf. index.css) : quatre valeurs,
   une seule source, et le défaut couvre une gravité que le flux aurait inventée. L'avant-plan vient
   de `--on-sev` et non de `--surface`, que cette règle détournait comme couleur de texte : en thème
   sombre, `--surface` devient foncé et retournerait le texte sur son fond. */
```

- [ ] **Step 9: exécuter — tout doit passer**

```bash
npm test && npm run build && npm run lint
```

Attendu : **PASS**, dont les deux cas nouvellement écrits. Le build doit être vert : la prop `label`
étant obligatoire, `tsc` aurait signalé un appel oublié.

- [ ] **Step 10: commit**

```bash
git add frontend/src/App.tsx frontend/src/App.module.css frontend/src/ui/PanelHeader.tsx frontend/src/ui/PanelHeader.test.tsx frontend/src/ui/PanelHeader.module.css frontend/src/ui/FloatingCard.tsx frontend/src/ui/FloatingCard.test.tsx frontend/src/ui/DisruptionRow.module.css
git commit -m "feat(ux-4): plan de document, régions nommées et avant-plan des puces de gravité"
```

---

### Task 6: `Échap` et retour du focus à la carte

**Aucun test automatique n'est possible ici** : tout vit dans `App`, qu'aucun test ne monte parce
qu'il construit MapLibre — périmètre explicitement exclu par la spec QUA-3. La vérification est la
recette navigateur. Ne pas inventer un faux MapLibre pour cette tâche.

**Files:**
- Modify: `frontend/src/App.tsx`

**Interfaces:**
- Consumes: rien des tâches précédentes.
- Produces: rien.

- [ ] **Step 1: séparer la remise à zéro de la station du retour de focus**

Dans `frontend/src/App.tsx`, remplacer `closeStation` (lignes ~265-271) par deux fonctions :

```tsx
  // Refermer une fiche détruit l'élément focalisé : sans retour explicite, le focus retombe sur
  // `body` et la tabulation repart du début du document. Le canevas est le seul point de retour
  // honnête — la fiche s'ouvre par un clic carte, il n'y a pas d'élément déclencheur à qui rendre le
  // focus — et il est focusable par construction (MapLibre y pose `tabindex="0"`).
  const focusMap = () => map?.getCanvas().focus();

  /** Vide la station sans toucher au focus : `followTrainFromPanel` enchaîne sur une autre fiche. */
  const resetStation = () => {
    departuresAbort.current?.abort();
    departuresAbort.current = null;
    setStation(null);
    setSelectedStationId(null);
    map?.setFilter("stops-selected", ["==", ["get", "id"], "__none__"]);
  };

  const closeStation = () => {
    resetStation();
    focusMap();
  };
```

- [ ] **Step 2: rendre le focus à la fermeture d'une fiche train**

Remplacer `clearSelection` (lignes ~259-263) :

```tsx
  const clearSelection = () => {
    setSelected(null);
    setSelectedJourneyRef(null);
    setFollow(false);
    focusMap();
  };
```

- [ ] **Step 3: faire enchaîner `followTrainFromPanel` sans voler le focus**

Remplacer l'appel `closeStation()` par `resetStation()` dans `followTrainFromPanel` :

```tsx
  const followTrainFromPanel = (journeyRef: string) => {
    resetStation();
    setSelected(null);
    setSelectedJourneyRef(journeyRef);
    setFollow(true);
  };
```

- [ ] **Step 4: brancher `Échap`**

À ajouter après les effets existants. Le motif du ref reprend celui de `sheet` déjà présent dans le
fichier :

```tsx
  // Écouteur sur `document`, et non un `onKeyDown` sur la fiche : au moment de fermer, le focus est
  // le plus souvent sur le canevas (la fiche s'ouvre par un clic carte), donc un gestionnaire React
  // posé sur le panneau ne verrait jamais la touche. Le ref suit le motif de `sheet` ci-dessus :
  // l'écouteur est posé une fois et ne peut pas lire ces valeurs sans les figer au montage.
  const onEscape = useRef(() => {});
  onEscape.current = () => {
    if (station || selectedStationId) {
      closeStation();
    } else if (selected || selectedJourneyRef) {
      clearSelection();
    }
  };

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        onEscape.current();
      }
    };
    document.addEventListener("keydown", onKeyDown);
    return () => document.removeEventListener("keydown", onKeyDown);
  }, []);
```

- [ ] **Step 5: vérifier le typage et le lint**

```bash
npm test && npm run build && npm run lint
```

Attendu : tests inchangés (aucun ne couvre `App`), typage vert, **lint muet** — vérifier en
particulier qu'`react-hooks/exhaustive-deps` ne réclame rien sur le `useEffect` à dépendances vides :
il ne lit que `onEscape.current`, jamais une valeur de rendu.

- [ ] **Step 6: commit**

```bash
git add frontend/src/App.tsx
git commit -m "feat(ux-4): Échap ferme la fiche et le focus revient à la carte"
```

---

### Task 7: Documentation

**Files:**
- Modify: `CLAUDE.md`
- Modify: `docs/roadmap.md`

**Interfaces:**
- Consumes: le résultat réel des tâches 1 à 6, dont le verdict de base d'axe consigné en tâche 2.
- Produces: rien.

- [ ] **Step 1: mettre CLAUDE.md à jour**

Dans la section « Conventions de code », amender la puce **Style : CSS Modules colocalisés** :

- la phrase « Ne subsistent que **deux** `style` inline, réduits à une variable CSS chacun » devient
  fausse : `LineBadge` en porte désormais **deux** (`--line-color` et `--line-fg`). Corriger le
  décompte, pas le supprimer.
- ajouter l'anneau de focus : règle globale `:focus-visible` dans `index.css`, et le fait qu'elle
  **atteint les contrôles MapLibre** là où `--tap` ne peut pas — donc amender aussi la limitation
  « `--tap` ne touche que nos composants » de la section « Limitations connues », qui reste vraie
  pour la taille des cibles mais plus pour le focus.
- ajouter le thème sombre : `prefers-color-scheme` seul, **panneaux uniquement**, et pourquoi la
  carte n'y est pas (renvoyer à UX-6).

Ajouter une puce sur la règle non évidente du chantier :

> **Les couleurs de ligne ne sont pas dessinées pour du blanc.** `LineBadge` calcule son avant-plan
> par `readableOn` (`ui/color.ts`) : mesuré, six des huit teintes réelles du flux échouent le seuil de
> 4,5:1 avec du blanc, jusqu'à 1,62:1 sur la ligne 9. Le choix se **calcule** au lieu de suivre un
> seuil de luminance, qui se tromperait sur la ligne 3. Ne pas « simplifier » en refixant `#fff`.

Et une puce sur le garde-fou et ses angles morts :

> **`axe-core` tourne dans les tests de composants** (`src/test/axe.ts`), mais **ne voit ni le
> contraste** (jsdom n'applique aucune feuille, la règle sort en *incomplete*) **ni les règles de
> niveau page** (désactivées : un composant monté seul n'a ni `main` ni `h1`). Il ne voit pas non plus
> qu'un bouton est une bascule : l'`aria-pressed` des pastilles est tenu par des assertions écrites à
> la main. Le contraste et le plan de document se vérifient au navigateur.

- [ ] **Step 2: ajouter la limitation 2.5.8**

Dans « Limitations connues » de `CLAUDE.md` :

> **Cibles de moins de 24 px sur écran large** : `--tap` vaut 0 au-dessus de 720 px, et `.chevron`
> (`padding: 0`) fait la hauteur de son glyphe. Le critère 2.5.8 de WCAG 2.2 demande 24 px ; l'écart
> est réel mais ne bloque ni le clavier ni le lecteur d'écran, et le corriger déplacerait la mise en
> page des deux cartes flottantes.

- [ ] **Step 3: clore UX-4 dans la roadmap**

Dans `docs/roadmap.md`, passer la ligne UX-4 à **fait**, avec un lien vers la spec et le plan. Dire
ce que le chantier a **réellement** trouvé, y compris ce que la fiche disait de faux :

- trois des quatre constats de la fiche étaient périmés ou mal placés (rôles largement posés depuis
  UX-2/QUA-8, information non portée par la seule couleur dans les panneaux, carte déjà pilotable au
  clavier) ;
- le vrai défaut, non listé : le blanc des pastilles de ligne, six teintes réelles sur huit sous le
  seuil, jusqu'à 1,62:1 ;
- ce que le garde-fou ne couvre pas, et le verdict de base d'axe tel qu'il a été mesuré en tâche 2 ;
- l'accès clavier aux **entités** de la carte reste chez UX-5.

- [ ] **Step 4: ouvrir UX-6**

Ajouter une ligne dans la section « UX / UI » :

> | UX-6 | Carte sombre et réentrance des couches | Le style `dark` d'OpenFreeMap existe et est servi
> par le même hôte que `liberty` (donc CSP inchangée), mais `map.setStyle()` vide sources et couches
> et **rien ne les repose** : `useNetwork` est câblé sur `[map]` seul, son `draw` sort sur
> `if (map.getSource("line-shapes")) return`, et `whenStyleReady` retire son écouteur `styledata` dès
> le premier succès ; `VehicleLayer` a la même forme. Après une bascule : plus de tracés, plus de
> stations, plus de trains, jusqu'au rechargement. Il faudrait aussi rejouer l'état qui vit dans des
> `setFilter` et non dans React (`stops-selected`, les trois filtres de perturbation, `visibleLines`,
> les deux anneaux de `journeyRef`), et reprendre sept couleurs écrites en dur pour un fond clair. Le
> vrai contenu du chantier est cette réentrance, qui profiterait surtout à un futur sélecteur de
> fond | M | P3 | à faire |

Mettre également à jour la section « Ordre recommandé » : UX-4 rejoint les chantiers faits.

- [ ] **Step 5: commit**

```bash
git add CLAUDE.md docs/roadmap.md
git commit -m "docs(ux-4): clôture du chantier, et le défaut que la fiche ne voyait pas"
```

---

## Compte de tests attendu

93 avant le chantier. +10 (tâche 2, axe) → 103. +10 (tâche 3 : 7 dans `color.test.ts`, 3 dans
`LineBadge.test.tsx`) → 113. +4 (tâche 4) → 117. +3 (tâche 5, `FloatingCard` ; le premier cas de
`PanelHeader` est *modifié*, pas ajouté) → **120**. Les tâches 1, 6 et 7 n'en ajoutent aucun, pour les
raisons écrites dans chacune. Un écart n'est pas une erreur en soi — mais il se dit, il ne s'ajuste
pas en silence.

## Recette navigateur

À jouer **après** la tâche 7, sur la pile lancée par l'utilisateur (ne pas la démarrer soi-même). Ce
que seul un navigateur montre — aucun test ne voit une règle CSS, et `color-contrast` est aveugle en
jsdom.

1. **Tabulation complète, au-dessus puis au-dessous de 720 px** : chaque contrôle atteignable,
   anneau visible sur chacun (dont la poignée, où il doit cercler le grip, et le bouton d'isolement,
   où il doit être arrondi), ordre cohérent, et **rien de focusable au cran `apercu`** hormis la
   poignée, l'alerte de gel et l'en-tête de fiche.
2. **`Échap`** ferme la fiche station puis la fiche train, le focus revient au canevas, et les
   flèches déplacent aussitôt la carte. Idem par le `✕`.
3. **`prefers-color-scheme: dark` forcé** dans les outils : les quatre tokens de texte, les deux
   puces claires conservées, l'anneau de focus, et le bord des panneaux contre une carte restée
   claire.
4. **Contraste mesuré à la pipette** sur `--text-faint`, sur le compteur d'une pastille masquée, et
   sur les pastilles de ligne dans les deux thèmes.
5. **La palette complète des 16 teintes**, relevée sur `/network`, passée à `readableOn` : c'est ce
   qui transforme l'échantillon de huit valeurs en couverture réelle. Signaler toute teinte où le
   meilleur des deux avant-plans reste sous 4,5:1.
6. **Anneau de focus sur les contrôles MapLibre** : vérifier qu'il s'y pose et qu'il ne double pas
   désagréablement leur propre style de focus (`box-shadow` de la feuille MapLibre).
7. **Passe au lecteur d'écran** (Orca ou NVDA) — le point coûteux, à trancher au moment de la
   recette : une pastille annonce-t-elle son état et son nom, les deux mises en page nomment-elles
   leurs panneaux, le `h1` ouvre-t-il bien le plan.
