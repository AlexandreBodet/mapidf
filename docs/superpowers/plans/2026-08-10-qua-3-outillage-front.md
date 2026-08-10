# QUA-3 — Harnais de composants et fonctions pures — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Pouvoir tester ce qui n'est pas une fonction pure — les panneaux et les gestes de la feuille — et couvrir les fonctions pures restantes, de sorte que les défauts qui ont coûté des tours de recette soient désormais détectables par `npm test`.

**Architecture:** Vitest est déjà là mais tourne sans configuration, en environnement Node. On lui ajoute un unique fichier de setup portant trois stubs (jsdom n'a pas de `ResizeObserver`, lève sur `setPointerCapture`, et renvoie 0 pour toute mesure), **sans** changer l'environnement global : chaque fichier de test de composant déclare `// @vitest-environment jsdom`. Deux fonctions sont extraites pour devenir testables (`badgeText`, `toggleLine`) ; aucun composant n'est modifié.

**Tech Stack:** Vitest 2.1.9, jsdom, @testing-library/react + /dom + /user-event, React 18, TypeScript 5.6.

## Global Constraints

Copiées de la spec [2026-08-10-qua-3-outillage-front-design.md](../specs/2026-08-10-qua-3-outillage-front-design.md). Elles s'appliquent à **toutes** les tâches.

- **Aucun changement de comportement visible dans l'application.** Ce chantier ne livre que des tests et deux extractions à isopérimètre. Si un test exige de modifier le composant qu'il teste, c'est un **constat à remonter**, pas une retouche à faire au passage.
- **L'environnement global reste Node.** Seuls les fichiers montant un composant portent `// @vitest-environment jsdom` en première ligne. Les 25 tests de fonctions pures existants ne doivent pas payer le coût de jsdom.
- **Versions exactes des dépendances**, toutes en `devDependencies` : `jsdom@^26`, `@testing-library/react@^16`, `@testing-library/dom@^10`, `@testing-library/user-event@^14`. `@testing-library/dom` est **obligatoire et explicite** : depuis la v16, `@testing-library/react` en fait un pair, plus une dépendance transitive.
- **Pas de `@testing-library/jest-dom`** : les assertions se font en TypeScript nu (`expect(el.style.textDecoration).toBe("line-through")`, `expect(screen.queryByText(…)).not.toBeNull()`). Une dépendance de moins.
- **Commentaires sobres** : uniquement le « pourquoi » non-évident, 1 à 2 lignes. Ce projet en écrit peu.
- **Messages de commit en français**, comme ceux donnés par chaque tâche.
- **Ne jamais démarrer ni arrêter les applications de l'utilisateur** (backend, `npm run dev`, Docker). `npm test` et `npm run build` sont autorisés.

---

## Structure des fichiers

| Fichier | Responsabilité | Tâche |
|---|---|---|
| `frontend/package.json` | **Modifié.** Les quatre devDependencies | 1 |
| `frontend/vite.config.ts` | **Modifié.** Bloc `test` avec `setupFiles`, et l'import de `defineConfig` déplacé vers `vitest/config` | 1 |
| `frontend/src/test/setup.ts` | **Créé.** Les trois stubs, plus `triggerResize()` et `stubHeight()` | 1 |
| `frontend/src/ui/NetworkSummary.test.tsx` | **Créé.** Le compteur, l'état hors service, le pluriel des lignes perturbées | 1, 4 |
| `frontend/src/ui/status.test.ts` | **Créé.** `statusKind` / `statusLabel` | 2 |
| `frontend/src/ui/severity.test.ts` | **Créé.** `severityStyle` | 2 |
| `frontend/src/ui/disruptionText.ts` | **Renommé** depuis `disruptionTitle.ts`, et `badgeText` y arrive | 3 |
| `frontend/src/ui/disruptionText.test.ts` | **Renommé** depuis `disruptionTitle.test.ts`, plus les cas de `badgeText` | 3 |
| `frontend/src/ui/DisruptionRow.tsx` | **Modifié.** Import du module renommé, `badgeText` retirée | 3 |
| `frontend/src/ui/toggleLine.ts` | **Créé.** Extraite d'`App.tsx` | 3 |
| `frontend/src/ui/toggleLine.test.ts` | **Créé.** Les quatre règles | 3 |
| `frontend/src/App.tsx` | **Modifié.** Appelle `toggleLine` au lieu de porter sa logique | 3 |
| `frontend/src/ui/StopPanel.test.tsx` | **Créé.** Cinq comportements, dont deux régressions livrées | 4 |
| `frontend/src/ui/DisruptionRow.test.tsx` | **Créé.** Bouton seulement s'il y a un détail | 4 |
| `frontend/src/ui/Sheet.test.tsx` | **Créé.** Les gestes | 5 |
| `docs/roadmap.md`, `CLAUDE.md` | **Modifiés.** QUA-3 → `fait`, et comment les tests front sont organisés | 6 |

---

## Task 1 : le harnais, prouvé par un premier test de composant

**Files:**
- Modify: `frontend/package.json`
- Modify: `frontend/vite.config.ts`
- Create: `frontend/src/test/setup.ts`
- Create: `frontend/src/ui/NetworkSummary.test.tsx`

**Interfaces:**
- Consumes: rien.
- Produces: `triggerResize(): void` et `stubHeight(element: Element, px: number): void`, exportés par `src/test/setup.ts`. Les tâches 4 et 5 les importent via `../test/setup`.

- [ ] **Step 1 : installer les quatre dépendances**

```bash
cd frontend && npm install --save-dev jsdom@^26 @testing-library/react@^16 @testing-library/dom@^10 @testing-library/user-event@^14
```

Attendu : installation sans `ERESOLVE`. Si npm réclame un pair manquant, **ne pas** utiliser `--legacy-peer-deps` : le signaler dans le rapport, c'est un constat sur les versions.

- [ ] **Step 2 : écrire le fichier de setup**

Créer `frontend/src/test/setup.ts` avec exactement ce contenu :

```ts
import { beforeEach } from "vitest";

/**
 * `Sheet` installe un ResizeObserver au montage, et jsdom n'en a pas : sans ce stub, le composant
 * lève. Il n'observe rien — c'est au test de dire quand la mesure tombe, via `triggerResize`.
 */
class ResizeObserverStub {
  static instances: ResizeObserverStub[] = [];

  constructor(private readonly callback: ResizeObserverCallback) {
    ResizeObserverStub.instances.push(this);
  }

  observe() {}
  unobserve() {}
  disconnect() {}

  fire() {
    this.callback([], this as unknown as ResizeObserver);
  }
}

// Ce fichier est chargé AUSSI pour les tests de fonctions pures, qui tournent en environnement
// Node : sans ce garde, `Element` n'existe pas et tous ces tests casseraient.
if (typeof Element !== "undefined") {
  globalThis.ResizeObserver = ResizeObserverStub as unknown as typeof ResizeObserver;
  // jsdom LÈVE sur ces deux méthodes : le premier geste de chaque test échouerait.
  Element.prototype.setPointerCapture = () => {};
  Element.prototype.releasePointerCapture = () => {};
}

beforeEach(() => {
  ResizeObserverStub.instances.length = 0;
});

/** Déclenche la mesure de tous les observers installés. */
export function triggerResize() {
  for (const observer of ResizeObserverStub.instances) {
    observer.fire();
  }
}

/**
 * jsdom renvoie 0 pour toute mesure. Un stub par élément, pas sur le prototype : un test qui
 * impose une hauteur doit dire de quel élément il parle.
 */
export function stubHeight(element: Element, px: number) {
  element.getBoundingClientRect = () => ({
    height: px, width: 0, top: 0, left: 0, right: 0, bottom: px, x: 0, y: 0,
    toJSON: () => ({}),
  });
}
```

- [ ] **Step 3 : brancher le setup dans la configuration Vite**

Dans `frontend/vite.config.ts`, remplacer la première ligne :

```ts
import { defineConfig, loadEnv } from "vite";
```

par :

```ts
// `defineConfig` vient de vitest/config, sans quoi `tsc -b` rejette la clé `test` inconnue.
import { defineConfig } from "vitest/config";
import { loadEnv } from "vite";
```

Puis ajouter la clé `test` dans l'objet retourné, juste après `plugins` :

```ts
    plugins: [react()],
    // Environnement Node par défaut : seuls les fichiers montant un composant demandent jsdom,
    // par un `// @vitest-environment jsdom` en tête de fichier.
    test: { setupFiles: ["./src/test/setup.ts"] },
```

- [ ] **Step 4 : écrire le premier test de composant (celui qui échoue)**

Créer `frontend/src/ui/NetworkSummary.test.tsx` :

```tsx
// @vitest-environment jsdom
import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { NetworkSummary } from "./NetworkSummary";

// Sans `globals: true`, le nettoyage automatique de Testing Library ne s'enregistre pas : les
// rendus s'accumuleraient d'un test à l'autre et les recherches trouveraient plusieurs éléments.
afterEach(cleanup);

function renderSummary(props: Partial<Parameters<typeof NetworkSummary>[0]> = {}) {
  return render(
    <NetworkSummary
      total={12}
      inService
      disruptedCount={0}
      disruptionsOpen={false}
      onToggleDisruptions={vi.fn()}
      canShowAll={false}
      onShowAll={vi.fn()}
      {...props}
    />,
  );
}

describe("NetworkSummary", () => {
  it("annonce le service terminé au lieu d'un compteur, hors des heures de service", () => {
    // Régression livrée : la nuit, « 705 trains en circulation » alors que le flux est éteint.
    renderSummary({ inService: false, total: 705 });

    expect(screen.queryByText("Service terminé")).not.toBeNull();
    expect(screen.queryByText("Reprise au premier métro.")).not.toBeNull();
    expect(screen.queryByText(/trains en circulation/)).toBeNull();
  });
});
```

- [ ] **Step 5 : lancer le test et vérifier qu'il PASSE, puis prouver qu'il discrimine**

Run: `cd frontend && npx vitest run src/ui/NetworkSummary.test.tsx`
Expected: PASS. Le comportement est déjà correct — ce test le **garde**.

Puis prouver qu'il rougirait si le bug revenait : dans `src/ui/NetworkSummary.tsx`, remplacer temporairement

```tsx
        <b>{inService ? `${total} trains en circulation` : "Service terminé"}</b>
```

par

```tsx
        <b>{`${total} trains en circulation`}</b>
```

Relancer : le test doit **ÉCHOUER**. Puis **rétablir la ligne d'origine** et relancer : PASS. Consigner les deux sorties dans le rapport — un test de régression qui n'a jamais échoué ne prouve rien.

- [ ] **Step 6 : vérifier que les tests de fonctions pures n'ont pas régressé**

Run: `cd frontend && npm test`
Expected: 26 tests passés (25 existants + 1 nouveau), et **aucune erreur du type `Element is not defined`** — c'est le garde du Step 2 qui le prévient.

- [ ] **Step 7 : vérifier que le build typé passe**

Run: `cd frontend && npm run build`
Expected: succès. Si `tsc -b` se plaint de la clé `test`, l'import de `defineConfig` du Step 3 n'a pas été fait.

- [ ] **Step 8 : commit**

```bash
git add frontend/package.json frontend/package-lock.json frontend/vite.config.ts frontend/src/test/setup.ts frontend/src/ui/NetworkSummary.test.tsx
git commit -m "test(qua-3): harnais de composants (jsdom + Testing Library) et premier test"
```

---

## Task 2 : les deux fonctions pures testables en l'état

**Files:**
- Create: `frontend/src/ui/status.test.ts`
- Create: `frontend/src/ui/severity.test.ts`

**Interfaces:**
- Consumes: rien des tâches précédentes. Ces deux fichiers tournent en environnement **Node** — pas de `@vitest-environment`.
- Produces: rien.

**Contexte pour l'implémenteur.** `statusKind(status)` mappe les valeurs SCREAMING_SNAKE de PRIM vers `"onTime" | "delayed" | "early" | "cancelled" | "unknown"`, et `statusLabel` en donne le libellé français. `severityStyle(severity)` renvoie `{ color, glyph, label }` pour les quatre gravités du flux IDFM.

- [ ] **Step 1 : écrire les tests de `status`**

Créer `frontend/src/ui/status.test.ts` :

```ts
import { describe, expect, it } from "vitest";
import { statusKind, statusLabel } from "./status";

describe("statusKind", () => {
  it("reconnaît les deux valeurs mesurées sur le métro", () => {
    expect(statusKind("ON_TIME")).toBe("onTime");
    expect(statusKind("DELAYED")).toBe("delayed");
  });

  it("admet les variantes d'orthographe du flux SIRI", () => {
    expect(statusKind("ONTIME")).toBe("onTime");
    expect(statusKind("EARLY")).toBe("early");
    expect(statusKind("CANCELED")).toBe("cancelled");
    expect(statusKind("CANCELLED")).toBe("cancelled");
  });

  it("ignore la casse", () => {
    expect(statusKind("on_time")).toBe("onTime");
  });

  it("tombe sur unknown plutôt que d'afficher une valeur inédite telle quelle", () => {
    // PRIM peut inventer un statut : il ne doit jamais sortir brut à l'écran.
    expect(statusKind("QUELQUE_CHOSE_DE_NEUF")).toBe("unknown");
    expect(statusKind(null)).toBe("unknown");
    expect(statusKind(undefined)).toBe("unknown");
  });
});

describe("statusLabel", () => {
  it("traduit les états connus", () => {
    expect(statusLabel("ON_TIME")).toBe("à l'heure");
    expect(statusLabel("DELAYED")).toBe("retardé");
    expect(statusLabel("EARLY")).toBe("en avance");
    expect(statusLabel("CANCELLED")).toBe("supprimé");
  });

  it("ne prétend rien sur un état inconnu", () => {
    expect(statusLabel("INEDIT")).toBe("—");
  });
});
```

- [ ] **Step 2 : écrire les tests de `severity`**

Créer `frontend/src/ui/severity.test.ts` :

```ts
import { describe, expect, it } from "vitest";
import { severityStyle } from "./severity";

describe("severityStyle", () => {
  it("donne une couleur ET un glyphe à chaque gravité", () => {
    // Règle d'accessibilité du projet : jamais d'information portée par la seule couleur —
    // 13/3bis et 6/7bis partagent déjà leur teinte sur la carte.
    for (const severity of ["BLOQUANTE", "PERTURBEE", "INFORMATION", "INCONNUE"] as const) {
      const style = severityStyle(severity);
      expect(style.color).toMatch(/^#[0-9a-f]{6}$/);
      expect(style.glyph).not.toBe("");
      expect(style.label).not.toBe("");
    }
  });

  it("distingue les gravités entre elles", () => {
    // Vérifier que les quatre gravités ont des couleurs et glyphes distincts.
    const severities = ["BLOQUANTE", "PERTURBEE", "INFORMATION", "INCONNUE"] as const;
    const styles = severities.map(severityStyle);
    const colors = styles.map((s) => s.color);
    const glyphs = styles.map((s) => s.glyph);
    expect(new Set(colors)).toHaveLength(4);
    expect(new Set(glyphs)).toHaveLength(4);
  });

  it("retombe sur INCONNUE pour une gravité que le flux aurait inventée", () => {
    expect(severityStyle("INEDITE" as never)).toEqual(severityStyle("INCONNUE"));
  });
});
```

- [ ] **Step 3 : lancer les deux fichiers**

Run: `cd frontend && npx vitest run src/ui/status.test.ts src/ui/severity.test.ts`
Expected: PASS, 9 tests. Ces fonctions sont déjà correctes ; ces tests les gardent.

- [ ] **Step 4 : vérifier qu'ils tournent bien en environnement Node**

Run: `cd frontend && npx vitest run src/ui/status.test.ts --reporter=verbose 2>&1 | head -20`
Expected : aucune mention de `jsdom`. Si jsdom apparaît, c'est que l'environnement a été rendu global par erreur — le corriger dans `vite.config.ts`.

- [ ] **Step 5 : commit**

```bash
git add frontend/src/ui/status.test.ts frontend/src/ui/severity.test.ts
git commit -m "test(qua-3): couvre statusKind/statusLabel et severityStyle"
```

---

## Task 3 : les deux extractions

**Files:**
- Rename: `frontend/src/ui/disruptionTitle.ts` → `frontend/src/ui/disruptionText.ts`
- Rename: `frontend/src/ui/disruptionTitle.test.ts` → `frontend/src/ui/disruptionText.test.ts`
- Modify: `frontend/src/ui/DisruptionRow.tsx`
- Create: `frontend/src/ui/toggleLine.ts`
- Create: `frontend/src/ui/toggleLine.test.ts`
- Modify: `frontend/src/App.tsx:92-117`

**Interfaces:**
- Consumes: rien des tâches précédentes.
- Produces:
  - `disruptionTitle(title: string, lineShown: boolean): string` et `badgeText(shortMessage: string, fallback: string): string`, tous deux exportés par `ui/disruptionText.ts`.
  - `toggleLine(current: Set<string> | null, lineId: string, lineCount: number): Set<string> | null`, exportée par `ui/toggleLine.ts`. La tâche 4 n'en a pas besoin ; personne d'autre ne la consomme.

- [ ] **Step 1 : renommer le module et y déplacer `badgeText`**

```bash
cd frontend && git mv src/ui/disruptionTitle.ts src/ui/disruptionText.ts && git mv src/ui/disruptionTitle.test.ts src/ui/disruptionText.test.ts
```

Puis **ajouter** à la fin de `src/ui/disruptionText.ts` la fonction, déplacée telle quelle depuis `DisruptionRow.tsx` (commentaire compris) :

```ts
/**
 * Le flux met « Autre » en résumé quand il n'en a pas — mesuré sur « Métro 14 / 5 / 4 :
 * Information - Autre », dont tout le sens était dans le message. Le libellé de gravité en dit
 * alors davantage.
 */
export function badgeText(shortMessage: string, fallback: string): string {
  return !shortMessage || shortMessage.toLowerCase() === "autre" ? fallback : shortMessage;
}
```

- [ ] **Step 2 : retirer `badgeText` de `DisruptionRow.tsx` et corriger l'import**

Dans `src/ui/DisruptionRow.tsx`, supprimer la fonction `badgeText` et son commentaire, puis remplacer

```tsx
import { disruptionTitle } from "./disruptionTitle";
```

par

```tsx
import { badgeText, disruptionTitle } from "./disruptionText";
```

- [ ] **Step 3 : ajouter les cas de `badgeText` au fichier de test renommé**

Dans `src/ui/disruptionText.test.ts`, changer l'import de tête en

```ts
import { badgeText, disruptionTitle } from "./disruptionText";
```

et ajouter, après le `describe` existant (qui reste inchangé) :

```ts
describe("badgeText", () => {
  it("garde le résumé du flux quand il dit quelque chose", () => {
    expect(badgeText("Arrêt non desservi", "trafic bloqué")).toBe("Arrêt non desservi");
  });

  it("préfère le libellé de gravité au « Autre » du flux, qui ne dit rien", () => {
    expect(badgeText("Autre", "information")).toBe("information");
    expect(badgeText("autre", "information")).toBe("information");
  });

  it("préfère le libellé de gravité à un résumé vide", () => {
    expect(badgeText("", "trafic perturbé")).toBe("trafic perturbé");
  });
});
```

- [ ] **Step 4 : vérifier le renommage**

Run: `cd frontend && npm test 2>&1 | tail -6 && npm run build 2>&1 | tail -2`
Expected: **aucun échec**, et build vert. Si `tsc -b` se plaint d'un import `./disruptionTitle` introuvable, un import a été oublié — le chercher par `grep -rn disruptionTitle src/`.

- [ ] **Step 5 : écrire les tests de `toggleLine` (qui échouent, le module n'existe pas)**

Créer `frontend/src/ui/toggleLine.test.ts` :

```ts
import { describe, expect, it } from "vitest";
import { toggleLine } from "./toggleLine";

// 16 lignes de métro suivies : c'est ce nombre qui décide du retour à « toutes ».
const TOTAL = 16;

describe("toggleLine", () => {
  it("isole la ligne cliquée depuis l'état « toutes »", () => {
    // Retirer une ligne sur 16 demanderait 15 clics : l'intention la plus fréquente est
    // d'en voir une seule.
    expect(toggleLine(null, "9", TOTAL)).toEqual(new Set(["9"]));
  });

  it("retire une ligne quand il en reste d'autres", () => {
    expect(toggleLine(new Set(["3", "9"]), "3", TOTAL)).toEqual(new Set(["9"]));
  });

  it("ajoute une ligne au sous-ensemble affiché", () => {
    expect(toggleLine(new Set(["3"]), "9", TOTAL)).toEqual(new Set(["3", "9"]));
  });

  it("ne vide jamais la carte : cliquer la dernière ligne visible ne fait rien", () => {
    const current = new Set(["9"]);

    // `toBe` et non `toEqual` : renvoyer un Set neuf pour un no-op déclencherait un re-render
    // et le refiltrage des 321 stations pour rien.
    expect(toggleLine(current, "9", TOTAL)).toBe(current);
  });

  it("revient à « toutes » quand le clic complète l'ensemble", () => {
    const current = new Set(["3", "9"]);

    expect(toggleLine(current, "11", 3)).toBeNull();
  });

  it("ne prétend pas « toutes » quand le réseau n'est pas encore chargé", () => {
    // lineCount = 0 : c'est l'état avant le premier /network. Le sous-ensemble doit survivre.
    expect(toggleLine(new Set(["3"]), "9", 0)).toEqual(new Set(["3", "9"]));
  });
});
```

- [ ] **Step 6 : lancer et vérifier l'échec**

Run: `cd frontend && npx vitest run src/ui/toggleLine.test.ts`
Expected: FAIL — `Failed to load url ./toggleLine`.

- [ ] **Step 7 : créer le module**

Créer `frontend/src/ui/toggleLine.ts` :

```ts
/**
 * Prochain sous-ensemble de lignes visibles après un clic. `null` = toutes.
 *
 * `lineCount` est un nombre, pas une liste : seule la taille sert, à décider du retour à
 * « toutes ». Extraite d'`App` pour que ses quatre règles soient testables.
 */
export function toggleLine(
  current: Set<string> | null,
  lineId: string,
  lineCount: number,
): Set<string> | null {
  // Premier clic depuis « toutes » : on isole la ligne cliquée plutôt que de la retirer d'un
  // ensemble complet — c'est l'intention la plus fréquente sur 16 lignes.
  if (current === null) {
    return new Set([lineId]);
  }
  const next = new Set(current);
  if (next.has(lineId)) {
    // Ne pas vider la carte d'un clic. `current`, pas `next` : renvoyer un Set neuf pour un
    // no-op déclencherait un re-render et le refiltrage des 321 stations pour rien.
    if (next.size === 1) {
      return current;
    }
    next.delete(lineId);
  } else {
    next.add(lineId);
  }
  return next.size === lineCount ? null : next;
}
```

- [ ] **Step 8 : lancer et vérifier le passage**

Run: `cd frontend && npx vitest run src/ui/toggleLine.test.ts`
Expected: PASS, 6 tests.

- [ ] **Step 9 : brancher `App.tsx` sur la fonction extraite**

Dans `src/App.tsx`, remplacer tout le corps de `toggleLine` (lignes 92 à 117, du `const toggleLine =` jusqu'à son `};`) par :

```tsx
  const toggleLine = (lineId: string) => {
    setVisibleLines((current) => toggleLineSubset(current, lineId, network?.lines.length ?? 0));
  };
```

et ajouter l'import, dans le groupe des imports `./ui/…` :

```tsx
import { toggleLine as toggleLineSubset } from "./ui/toggleLine";
```

L'alias évite de masquer le nom local `toggleLine` que `LinePicker` reçoit en prop.

- [ ] **Step 10 : vérifier que rien n'a bougé**

Run: `cd frontend && npm test 2>&1 | tail -5 && npm run build 2>&1 | tail -2`
Expected: aucun échec, build vert. Puis `grep -n "all = new Set" src/App.tsx` → **aucun résultat** : l'ancienne construction de l'ensemble complet a bien disparu.

- [ ] **Step 11 : commit**

```bash
git add frontend/src/ui/disruptionText.ts frontend/src/ui/disruptionText.test.ts frontend/src/ui/DisruptionRow.tsx frontend/src/ui/toggleLine.ts frontend/src/ui/toggleLine.test.ts frontend/src/App.tsx
git commit -m "refactor(qua-3): extrait badgeText et toggleLine, désormais testables"
```

---

## Task 4 : les panneaux, dont deux régressions livrées

**Files:**
- Create: `frontend/src/ui/StopPanel.test.tsx`
- Create: `frontend/src/ui/DisruptionRow.test.tsx`
- Modify: `frontend/src/ui/NetworkSummary.test.tsx` (compléter le fichier créé en tâche 1)

**Interfaces:**
- Consumes: le harnais de la tâche 1 (les fichiers portent `// @vitest-environment jsdom`).
- Produces: rien.

**Contexte pour l'implémenteur.** `StopPanel` reçoit une `DeparturesResponse` : `{ stationName, disruptions: DisruptionItem[], lines: [{ lineId, shortName, color, directions: [{ destination, passages: [{ journeyRef, expectedTime, status }] }] }] }`. Un `DisruptionItem` est `{ severity, cause, title, shortMessage, detail }`. Le panneau filtre lui-même les passages déjà partis, en comparant `expectedTime` à l'instant courant — les fixtures doivent donc être **relatives à `Date.now()`**.

- [ ] **Step 1 : écrire les tests de `StopPanel`**

Créer `frontend/src/ui/StopPanel.test.tsx` :

```tsx
// @vitest-environment jsdom
import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { DeparturesResponse } from "../api/types";
import { StopPanel } from "./StopPanel";

// Sans `globals: true`, le nettoyage automatique de Testing Library ne s'enregistre pas.
afterEach(cleanup);

// +5 s de marge, sans quoi les millisecondes écoulées entre la fixture et le rendu font tomber
// le `Math.floor(sec / 60)` de formatEta sur la minute inférieure : le test serait intermittent.
const inMinutes = (min: number) => new Date(Date.now() + min * 60_000 + 5_000).toISOString();

function departures(overrides: Partial<DeparturesResponse> = {}): DeparturesResponse {
  return {
    stationName: "République",
    disruptions: [],
    lines: [
      {
        lineId: "3", shortName: "3", color: "#CEADD2",
        directions: [
          {
            destination: "Gallieni",
            passages: [
              { journeyRef: "J-1", expectedTime: inMinutes(3), status: "ON_TIME" },
              { journeyRef: "J-2", expectedTime: inMinutes(7), status: "ON_TIME" },
            ],
          },
        ],
      },
    ],
    ...overrides,
  };
}

describe("StopPanel", () => {
  it("affiche les horaires au format compact, pour que trois tiennent sur une ligne", () => {
    // Régression livrée : « dans 3 min » faisait ~237 px à trois, pour 260 disponibles, donc un
    // repli imprévisible et un séparateur orphelin en début de ligne.
    render(<StopPanel data={departures()} />);

    expect(screen.queryByText("3 min")).not.toBeNull();
    expect(screen.queryByText("dans 3 min")).toBeNull();
  });

  it("dit quelle ligne n'est pas desservie", () => {
    // Régression livrée : à République, correspondance à cinq lignes, la fiche affichait
    // « Arrêt non desservi » sans jamais dire qu'il s'agissait de la 8.
    render(<StopPanel data={departures({
      disruptions: [{
        severity: "BLOQUANTE", cause: "TRAVAUX",
        title: "Métro 8 : Travaux de rénovation - Arrêt non desservi",
        shortMessage: "Arrêt non desservi", detail: "",
      }],
    })} />);

    expect(screen.queryByText("Métro 8 : Travaux de rénovation - Arrêt non desservi")).not.toBeNull();
  });

  it("masque un passage déjà parti, et la ligne qui n'a plus rien à annoncer", () => {
    render(<StopPanel data={departures({
      lines: [{
        lineId: "3", shortName: "3", color: "#CEADD2",
        directions: [{
          destination: "Gallieni",
          passages: [{ journeyRef: "J-0", expectedTime: inMinutes(-2), status: "ON_TIME" }],
        }],
      }],
    })} />);

    expect(screen.queryByText("Gallieni")).toBeNull();
    expect(screen.queryByText("Aucun passage annoncé.")).not.toBeNull();
  });

  it("barre un passage supprimé et le dit, au lieu d'une heure en bleu comme les autres", () => {
    render(<StopPanel data={departures({
      lines: [{
        lineId: "3", shortName: "3", color: "#CEADD2",
        directions: [{
          destination: "Gallieni",
          passages: [{ journeyRef: "J-1", expectedTime: inMinutes(4), status: "CANCELLED" }],
        }],
      }],
    })} />);

    expect(screen.queryByText("supprimé")).not.toBeNull();
    const heure = screen.getByText("4 min");
    expect(heure.style.textDecoration).toBe("line-through");
  });

  it("remonte le journeyRef du passage cliqué", () => {
    const onSelectTrain = vi.fn();
    render(<StopPanel data={departures()} onSelectTrain={onSelectTrain} />);

    screen.getByText("7 min").click();

    expect(onSelectTrain).toHaveBeenCalledWith("J-2");
  });
});
```

- [ ] **Step 2 : lancer les tests de `StopPanel`**

Run: `cd frontend && npx vitest run src/ui/StopPanel.test.tsx`
Expected: PASS, 5 tests. Si le clic du dernier test ne déclenche rien, remplacer `.click()` par `fireEvent.click(...)` importé de `@testing-library/react` — le signaler dans le rapport.

- [ ] **Step 3 : prouver que les deux tests de régression discriminent**

Pour le format compact : dans `src/ui/formatEta.ts`, remettre temporairement `` `dans ${min} min` `` à la place de `` `${min} min` ``. Relancer `npx vitest run src/ui/StopPanel.test.tsx` → **ÉCHEC** attendu, puis rétablir.

Pour le préfixe de ligne : dans `src/ui/DisruptionRow.tsx`, remplacer temporairement `leading != null` par `true` dans l'appel à `disruptionTitle`. Relancer → **ÉCHEC** attendu, puis rétablir.

Consigner les quatre sorties (échec puis retour au vert, pour chacun) dans le rapport.

- [ ] **Step 4 : compléter les tests de `NetworkSummary`**

Dans `src/ui/NetworkSummary.test.tsx`, ajouter à l'intérieur du `describe` existant :

```tsx
  it("compte les trains quand le service est ouvert", () => {
    renderSummary({ total: 12 });

    expect(screen.queryByText("12 trains en circulation")).not.toBeNull();
    expect(screen.queryByText("Service terminé")).toBeNull();
  });

  it("accorde le nombre de lignes perturbées", () => {
    const seule = renderSummary({ disruptedCount: 1 });
    expect(screen.queryByText(/^1 ligne perturbée/)).not.toBeNull();
    seule.unmount();

    renderSummary({ disruptedCount: 3 });
    expect(screen.queryByText(/^3 lignes perturbées/)).not.toBeNull();
  });

  it("n'offre « tout afficher » que si un sous-ensemble est isolé", () => {
    const tout = renderSummary({ canShowAll: false });
    expect(screen.queryByText("tout afficher")).toBeNull();
    tout.unmount();

    renderSummary({ canShowAll: true });
    expect(screen.queryByText("tout afficher")).not.toBeNull();
  });
```

- [ ] **Step 5 : écrire le test de `DisruptionRow`**

Créer `frontend/src/ui/DisruptionRow.test.tsx` :

```tsx
// @vitest-environment jsdom
import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import type { DisruptionItem } from "../api/types";
import { DisruptionRow } from "./DisruptionRow";

// Sans `globals: true`, le nettoyage automatique de Testing Library ne s'enregistre pas.
afterEach(cleanup);

const item = (overrides: Partial<DisruptionItem> = {}): DisruptionItem => ({
  severity: "PERTURBEE", cause: "PERTURBATION",
  title: "Métro 5 : Incident - Train stationne",
  shortMessage: "Train stationne", detail: "",
  ...overrides,
});

describe("DisruptionRow", () => {
  it("ne rend cliquable que ce qui a un détail à révéler", () => {
    // Sinon le curseur mentirait : un bouton qui n'ouvre rien.
    const sans = render(<ul><DisruptionRow item={item()} /></ul>);
    expect(screen.queryByRole("button")).toBeNull();
    sans.unmount();

    render(<ul><DisruptionRow item={item({ detail: "Un train stationne à Bobigny." })} /></ul>);
    expect(screen.queryByRole("button")).not.toBeNull();
  });

  it("révèle le détail au clic, et le referme", () => {
    render(<ul><DisruptionRow item={item({ detail: "Un train stationne à Bobigny." })} /></ul>);

    expect(screen.queryByText("Un train stationne à Bobigny.")).toBeNull();
    screen.getByRole("button").click();
    expect(screen.queryByText("Un train stationne à Bobigny.")).not.toBeNull();
    screen.getByRole("button").click();
    expect(screen.queryByText("Un train stationne à Bobigny.")).toBeNull();
  });
});
```

- [ ] **Step 6 : lancer l'ensemble**

Run: `cd frontend && npm test 2>&1 | tail -8`
Expected: aucun échec.

- [ ] **Step 7 : commit**

```bash
git add frontend/src/ui/StopPanel.test.tsx frontend/src/ui/DisruptionRow.test.tsx frontend/src/ui/NetworkSummary.test.tsx
git commit -m "test(qua-3): panneaux — dont le préfixe de ligne et le format compact, régressions livrées"
```

---

## Task 5 : les gestes de la feuille

**Files:**
- Create: `frontend/src/ui/Sheet.test.tsx`

**Interfaces:**
- Consumes: `triggerResize()` et `stubHeight()` de `../test/setup` (tâche 1).
- Produces: rien.

**Contexte pour l'implémenteur.** `Sheet` reçoit `viewportHeight` en **prop** : les hauteurs de cran ne dépendent donc pas d'une mise en page que jsdom n'a pas. Les crans pour `viewportHeight = 844` valent 44 (`apercu`), 422 (`moitie`) et 760 (`plein`). Le glissement calcule `hauteur = startHeight + (startY - clientY)`, borné entre la hauteur d'aperçu mesurée et 760, puis `snap` retient le cran **le plus proche**.

**La vitesse est testable**, à condition de construire l'événement soi-même. jsdom 26 n'a pas de constructeur global `PointerEvent` : `fireEvent.pointerDown/Move/Up` retombe alors sur `Event` nu (cf. `@testing-library/dom`, `window[EventType] || window.Event`) et perd `clientY`/`pointerId` en route — mesuré : le geste atteint bien le gestionnaire, mais avec des coordonnées `undefined`, donc des hauteurs en `NaN`. Un `MouseEvent` construit à la main (lui bien supporté), avec `pointerId` posé en propriété brute et un `timeStamp` **non nul** en paramètre (React calcule `event.timeStamp || Date.now()`, donc `0` serait ignoré), rend `elapsed` — et donc la vitesse dans `applyMove`/`endDrag` — entièrement déterministe. C'est ce que fait `firePointer` au Step 1 : un `timeStamp` identique sur tout un geste annule la vitesse pour les tests qui n'en ont pas besoin ; un `timeStamp` qui varie la rend testable pour les coups secs. Le chemin « coup sec » (`flick`) est couvert à la fois par les tests unitaires de `snap` dans `sheetCrans.test.ts` (la fonction pure, isolée) et par les tests de vitesse de `Sheet.test.tsx` (l'acheminement réel depuis `applyMove`/`endDrag` jusqu'à `snap`, dont le signe — rien d'autre ne le couvre).

La poignée est un `<button aria-label="Changer la hauteur du panneau">`. Le corps qui défile est le parent de `children`.

- [ ] **Step 1 : écrire les tests**

Créer `frontend/src/ui/Sheet.test.tsx` :

```tsx
// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { stubHeight, triggerResize } from "../test/setup";
import { Sheet } from "./Sheet";
import type { Cran } from "./sheetCrans";

// Sans `globals: true`, le nettoyage automatique de Testing Library ne s'enregistre pas.
afterEach(cleanup);

const VIEWPORT = 844; // iPhone 12/13 en portrait, la cible du chantier UX-2.

/**
 * jsdom 26 n'expose pas de constructeur global `PointerEvent` : `fireEvent.pointerDown/Move/Up`
 * retombe alors sur `Event` nu (cf. `@testing-library/dom`, `createEvent`, qui utilise
 * `window[EventType] || window.Event`) et perd `clientY`/`pointerId` en route — le geste atteint
 * bien le gestionnaire de `Sheet`, mais avec des coordonnées `undefined`, donc des hauteurs
 * calculées en `NaN`. On construit donc l'événement à la main sur `MouseEvent`, lui correctement
 * supporté par jsdom, et on pose `pointerId` en propriété brute — elle ne sert qu'à
 * `setPointerCapture`, stubbé en no-op par `src/test/setup.ts`, donc sa valeur est inerte ici.
 * `timeStamp` est un paramètre (et non une constante) : React calcule
 * `event.timeStamp || Date.now()` (cf. `react-dom`), donc `0` serait ignoré et remplacé par
 * l'heure réelle — mais une valeur non nulle, choisie par l'appelant, rend `elapsed` (et donc la
 * vitesse dans `applyMove`/`endDrag`) entièrement déterministe. La vitesse **est** donc testable
 * ici : la valeur par défaut (identique sur tout un geste) l'annule pour les tests qui n'en ont
 * pas besoin ; les tests de coup sec ci-dessous l'écartent volontairement.
 */
function firePointer(
  element: Element,
  type: "pointerdown" | "pointermove" | "pointerup",
  clientY: number,
  timeStamp = 1,
) {
  const event = new MouseEvent(type, { bubbles: true, cancelable: true, clientY });
  Object.defineProperty(event, "pointerId", { value: 1 });
  Object.defineProperty(event, "timeStamp", { value: timeStamp });
  fireEvent(element, event);
}

// `timeStamp` identique (défaut) sur les trois événements : `elapsed` reste à 0 dans `applyMove`,
// donc la vitesse aussi — un glissement « lent », sans coup sec possible.
function drag(element: Element, fromY: number, toY: number) {
  firePointer(element, "pointerdown", fromY);
  firePointer(element, "pointermove", toY);
  firePointer(element, "pointerup", toY);
}

function renderSheet(cran: Cran) {
  const onCranChange = vi.fn();
  const onPeekHeight = vi.fn();
  render(
    <Sheet
      cran={cran}
      onCranChange={onCranChange}
      viewportHeight={VIEWPORT}
      header={null}
      summary={<p>résumé</p>}
      footer={<p>pied</p>}
      alert={null}
      label="État du réseau"
      onPeekHeight={onPeekHeight}
      asOf={null}
    >
      <p>corps</p>
    </Sheet>,
  );
  const handle = screen.getByRole("button", { name: "Changer la hauteur du panneau" });
  return { onCranChange, onPeekHeight, handle, body: screen.getByText("corps").parentElement! };
}

describe("Sheet — poignée", () => {
  it("change de cran quand on la tire vers le haut", () => {
    const { onCranChange, handle } = renderSheet("moitie");

    drag(handle, 400, 100);

    // 422 + 300 = 722, plus proche de 760 (plein) que de 422 (moitié).
    expect(onCranChange).toHaveBeenCalledWith("plein");
  });

  it("répond encore au clavier après un glissement", () => {
    // Le défaut exact d'UX-2 : `moved` n'était jamais remis à zéro, donc la poignée devenait
    // inerte au clavier dès le premier glissement. La correction lit `event.detail === 0`.
    const { onCranChange, handle } = renderSheet("apercu");

    drag(handle, 400, 300);
    onCranChange.mockClear();

    // `detail: 0` = activation clavier (Entrée/Espace), qui n'émet aucun événement pointeur.
    fireEvent.click(handle, { detail: 0 });

    expect(onCranChange).toHaveBeenCalledWith("moitie");
  });
});

describe("Sheet — poignée : vitesse d'un coup sec", () => {
  it("un coup sec vers le haut avance d'un cran de plus que le plus proche", () => {
    const { onCranChange, handle } = renderSheet("apercu");

    firePointer(handle, "pointerdown", 400, 100);
    firePointer(handle, "pointermove", 390, 105);
    firePointer(handle, "pointerup", 390, 106);

    // 44 + (400-390) = 54, plus proche de l'aperçu (44) que de moitié (422) : sans vitesse, le
    // cran resterait "apercu". Mais (400-390)/(105-100) = 2 px/ms, bien au-delà de FLICK (0.5,
    // cf. sheetCrans.ts) : le coup sec avance d'un cran de plus que le plus proche.
    expect(onCranChange).toHaveBeenCalledWith("moitie");
  });

  it("un coup sec vers le bas recule d'un cran de plus que le plus proche", () => {
    const { onCranChange, handle } = renderSheet("plein");

    firePointer(handle, "pointerdown", 100, 100);
    firePointer(handle, "pointermove", 110, 105);
    firePointer(handle, "pointerup", 110, 106);

    // 760 + (100-110) = 750, plus proche du plein (760) que de moitié (422) : sans vitesse, le
    // cran resterait "plein". Mais (100-110)/(105-100) = -2 px/ms, sous -FLICK (-0.5) : le coup
    // sec recule d'un cran de plus que le plus proche.
    expect(onCranChange).toHaveBeenCalledWith("moitie");
  });

  it("un dernier mouvement suivi d'une pause de plus de 60 ms retombe sur le cran le plus proche", () => {
    const { onCranChange, handle } = renderSheet("plein");

    firePointer(handle, "pointerdown", 100, 100);
    firePointer(handle, "pointermove", 200, 105);
    firePointer(handle, "pointerup", 200, 181);

    // 760 + (100-200) = 660, plus proche du plein (760, distance 100) que de moitié (422,
    // distance 238). Sans le lâcher retardé, (100-200)/(105-100) = -20 px/ms aurait déclenché un
    // coup sec vers le bas (recul à "moitie"). Mais le lâcher arrive 76 ms après le dernier
    // mouvement (> 60 ms) : la garde de Sheet.tsx remet la vitesse à 0, et c'est le cran le plus
    // proche, "plein", qui l'emporte.
    expect(onCranChange).toHaveBeenCalledWith("plein");
  });
});

describe("Sheet — poignée : clic du navigateur après un geste", () => {
  it("un tap immobile puis le clic natif du navigateur avancent d'un cran", () => {
    const { onCranChange, handle } = renderSheet("apercu");

    firePointer(handle, "pointerdown", 400);
    firePointer(handle, "pointerup", 400);
    onCranChange.mockClear();

    // `detail: 1` = clic natif (souris/tactile), qui suit tout pointerup côté navigateur réel —
    // aucun test précédent ne l'envoyait. Sans déplacement, `moved` est resté `false` : la
    // clause `!gesture.current.moved` de Sheet.tsx doit laisser passer.
    fireEvent.click(handle, { detail: 1 });

    expect(onCranChange).toHaveBeenCalledWith("moitie"); // nextCran("apercu")
  });

  it("le clic natif après un glissement n'avance pas d'un cran supplémentaire", () => {
    const { onCranChange, handle } = renderSheet("moitie");

    drag(handle, 400, 100); // atterrit sur "plein", comme le premier test de ce fichier.
    onCranChange.mockClear();

    // Même clic natif que ci-dessus, mais après un glissement réel : `moved` vaut `true`, donc
    // Sheet.tsx doit l'ignorer — sinon chaque glissement avancerait d'un cran de trop.
    fireEvent.click(handle, { detail: 1 });

    expect(onCranChange).not.toHaveBeenCalled();
  });
});

describe("Sheet — corps", () => {
  it("laisse le défilement gagner quand le contenu n'est pas remonté en haut", () => {
    const { onCranChange, body } = renderSheet("plein");
    Object.defineProperty(body, "scrollTop", { value: 50, configurable: true });

    drag(body, 100, 500);

    expect(onCranChange).not.toHaveBeenCalled();
  });

  it("replie la feuille jusqu'au cran le plus proche pour un glissement lent (sans vitesse)", () => {
    const { onCranChange, body } = renderSheet("plein");
    Object.defineProperty(body, "scrollTop", { value: 0, configurable: true });

    // `drag` pose le même `timeStamp` sur les trois événements : `elapsed` reste à 0, la
    // vitesse aussi. Sans ça, un glissement de cette amplitude est un coup sec (cf. test
    // suivant) et retombe un cran plus loin.
    drag(body, 100, 500);

    // 760 - 400 = 360, plus proche de 422 (moitié) que de 44 (aperçu).
    expect(onCranChange).toHaveBeenCalledWith("moitie");
  });

  it("un coup sec vers le bas depuis un contenu déjà en haut replie jusqu'à l'aperçu", () => {
    const { onCranChange, body } = renderSheet("plein");
    Object.defineProperty(body, "scrollTop", { value: 0, configurable: true });

    firePointer(body, "pointerdown", 100, 10);
    firePointer(body, "pointermove", 500, 15);
    firePointer(body, "pointerup", 500, 16);

    // Même amplitude que le glissement lent ci-dessus (760 - 400 = 360, plus proche de 422 que
    // 44), mais (100-500)/(15-10) = -80 px/ms, bien sous -FLICK (-0.5) : le coup sec pousse un
    // cran plus loin que le plus proche, jusqu'à l'aperçu.
    expect(onCranChange).toHaveBeenCalledWith("apercu");
  });
});

describe("Sheet — mesure de l'aperçu", () => {
  it("remonte la hauteur mesurée, dont App dérive le padding de caméra", () => {
    const { onPeekHeight, handle } = renderSheet("apercu");
    stubHeight(handle.parentElement!, 96);

    triggerResize();

    expect(onPeekHeight).toHaveBeenCalledWith(96);
  });
});
```

- [ ] **Step 2 : lancer les tests**

Run: `cd frontend && npx vitest run src/ui/Sheet.test.tsx`
Expected: PASS, 11 tests.

Deux pannes plausibles, et leur remède :
- `setPointerCapture is not a function` → le stub du Step 2 de la tâche 1 n'est pas chargé ; vérifier `setupFiles` dans `vite.config.ts`.
- `ResizeObserver is not defined` → même cause.

Si `fireEvent.pointerDown` n'atteint pas le gestionnaire (aucun appel à `onCranChange` sur le premier test), le signaler comme constat : cela voudrait dire que jsdom 26 ne construit pas d'événement `pointerdown` exploitable, et il faudrait alors passer par `userEvent.pointer(...)`. Ne pas modifier `Sheet` pour contourner.

- [ ] **Step 3 : prouver que le test du clavier discrimine**

Dans `src/ui/Sheet.tsx`, remplacer temporairement

```tsx
    if (event.detail === 0 || !gesture.current.moved) {
```

par

```tsx
    if (!gesture.current.moved) {
```

Relancer `npx vitest run src/ui/Sheet.test.tsx` : le test « répond encore au clavier après un glissement » doit **ÉCHOUER**. Rétablir la ligne d'origine, relancer : PASS. Consigner les deux sorties — c'est le défaut qui avait survécu à une revue et à une recette.

- [ ] **Step 4 : mesurer la durée totale de la suite**

Run: `cd frontend && npm test 2>&1 | tail -6`
Expected: aucun échec. **Relever la durée affichée** et la consigner dans le rapport : la cible est cinq secondes. Si elle est dépassée, ne rien optimiser — le rapporter, c'est un constat à arbitrer, pas un échec de la tâche.

- [ ] **Step 5 : commit**

```bash
git add frontend/src/ui/Sheet.test.tsx
git commit -m "test(qua-3): gestes de la feuille, dont le clavier après glissement"
```

---

## Task 6 : la documentation

**Files:**
- Modify: `docs/roadmap.md` (ligne QUA-3, et l'ordre recommandé)
- Modify: `CLAUDE.md` (section « Commandes », et une entrée de conventions)

**Interfaces:**
- Consumes: tout ce que les tâches 1 à 5 ont livré.
- Produces: rien.

- [ ] **Step 1 : mettre à jour la ligne QUA-3 de la feuille de route**

Dans `docs/roadmap.md`, remplacer le contenu de la colonne « Constat » **et** de la colonne « Statut » de la ligne QUA-3 par :

Constat :

```
**Fait.** Vitest est arrivé avec UX-2 ; QUA-3 y a ajouté un harnais de composants (jsdom + Testing Library, environnement déclaré par fichier pour que les tests de fonctions pures restent en Node) et les fonctions pures qui restaient. La liste d'origine de ce chantier était partiellement fausse, cf. § 2 de la spec : `color` n'existait pas, `badgeText` était privée, `toggleLine` était une fermeture inline
```

Statut :

```
**fait** — [spec](superpowers/specs/2026-08-10-qua-3-outillage-front-design.md). Trois régressions réellement livrées ont désormais un test qui rougit si on remet le bug (préfixe de ligne à République, « Service terminé », format `3 min`), et le défaut clavier-après-glissement d'UX-2 aussi. **Hors périmètre assumé** : `App.tsx` et la caméra (faux MapLibre complet pour un défaut déjà gardé par un test de `getPadding()`), le culling de `VehicleLayer`. **ESLint est parti avec QUA-5** et **Prettier avec QUA-8** — deux reformatages massifs coup sur coup se marcheraient dessus
```

- [ ] **Step 2 : corriger l'ordre recommandé**

Dans la section « Ordre recommandé » de `docs/roadmap.md`, ajouter `**QUA-3**` à la liste barrée des chantiers faits (point 1), et remplacer le point 2 par :

```
2. **QUA-8** (sortir du style inline) puis **UX-4** — QUA-3 a livré le harnais qui manquait pour
   convertir quinze composants sans régression muette. **QUA-5** peut se glisser avant QUA-8 : ses
   montées de majeure éteignent les 7 vulnérabilités de l'outillage (cf. SEC-6) et amènent ESLint.
```

- [ ] **Step 3 : dire dans CLAUDE.md comment les tests front sont organisés**

Dans `CLAUDE.md`, section « Commandes », sous le bloc `# Frontend (depuis frontend/)`, remplacer la ligne

```
npm run build          # build de prod — sert de vérif (pas de tests unitaires front)
```

par

```
npm run build          # build de prod — sert aussi de vérif de typage
npm test               # Vitest : fonctions pures en Node, composants en jsdom
```

Puis ajouter, à la fin de la liste de la section « Conventions de code » :

```markdown
- **Tests front : l'environnement est déclaré par fichier**, pas globalement — un
  `// @vitest-environment jsdom` en première ligne des tests de composants, rien pour les
  fonctions pures, qui restent en Node et rapides. `src/test/setup.ts` porte les trois stubs que
  jsdom impose (pas de `ResizeObserver`, `setPointerCapture` qui **lève**, toute mesure à 0) ; son
  garde `typeof Element !== "undefined"` est indispensable, ce fichier étant chargé aussi pour les
  tests qui tournent en Node. La **vitesse d'un geste n'est pas testable** ainsi (`fireEvent` ne
  fixe pas le `timeStamp`) : le coup sec est couvert par les tests unitaires de `snap`.
```

- [ ] **Step 4 : vérifier**

Run: `cd frontend && npm test 2>&1 | tail -4 && npm run build 2>&1 | tail -2`
Expected: aucun échec, build vert.

Puis relire les deux insertions en se demandant, pour chaque affirmation, si le code livré la rend vraie — en particulier le nom des fichiers et le contenu de `src/test/setup.ts`.

- [ ] **Step 5 : commit**

```bash
git add docs/roadmap.md CLAUDE.md
git commit -m "docs(qua-3): feuille de route et organisation des tests front"
```

---

## Recette (aucune, et c'est volontaire)

Ce chantier ne change rien à ce que l'utilisateur voit : il n'y a donc **pas** de recette navigateur. Les deux seules modifications de code applicatif sont des extractions à isopérimètre (`badgeText`, `toggleLine`), couvertes par leurs tests et par `npm run build`. Si un implémenteur se trouve obligé de modifier un composant pour faire passer un test, c'est un constat à remonter — pas une retouche à faire au passage.
