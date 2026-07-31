# UX-2 — Adaptation mobile : plan d'implémentation

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** rendre MapIDF utilisable sur téléphone en remplaçant, sous 720 px de large, les quatre
panneaux flottants par une feuille repliable unique à trois crans.

**Architecture:** la carte garde tout le viewport et la feuille flotte au-dessus ; un
`map.setPadding({ bottom })` par cran suffit à ce que tous les recentrages se posent au-dessus
d'elle. Les panneaux cessent de se positionner eux-mêmes : leur chrome part dans deux conteneurs
(`Sheet` en étroit, `FloatingCard` en large) et `App` choisit lequel monter. L'arithmétique des
crans est isolée hors de React, testée avec Vitest.

**Tech Stack:** React 18, TypeScript 5.6, Vite 5, MapLibre GL 4, Vitest 2 (ajouté ici).

**Spec de référence :** [2026-07-31-ux-2-adaptation-mobile-design.md](../specs/2026-07-31-ux-2-adaptation-mobile-design.md)

## Global Constraints

- **Toutes les commandes front se lancent depuis `frontend/`.**
- **Vérification de référence du front : `npm run build`** (il enchaîne `tsc -b` puis le build
  Vite). Après cette tâche 1, `npm test` s'y ajoute.
- **Seuil étroit : 720 px de large.** Écrit à deux endroits qui doivent rester d'accord :
  `NARROW_MAX_WIDTH` dans `src/ui/useViewport.ts` et la media query de `src/index.css`. Chacun
  porte un commentaire renvoyant à l'autre.
- **Aucune régression visible sur desktop.** Seule exception admise : la rangée flex du
  `PanelHeader` en remplacement du `float: right`.
- **Style inline partout, comme le reste du projet.** `src/index.css` n'accueille que ce que le
  style inline ne peut pas exprimer : media query, `env()`, `overscroll-behavior`.
- **Commentaires sobres** : uniquement le « pourquoi » non évident, en 1 à 2 lignes. Pas de
  commentaire qui paraphrase le code.
- **Ne jamais poser de `feature-state` sur la couche `vehicles`** (convention du projet, cf.
  CLAUDE.md). Aucune tâche de ce plan n'y touche.
- **Ne pas retirer** le pied de licence du `LinePicker` (« position estimée » + heure du
  snapshot) : obligation art. 5.7 de la Licence Mobilité. Il reste dans `LinePicker`.
- **Ne pas démarrer ni arrêter le serveur de dev** : l'utilisateur gère ses applications.

---

### Task 1: Vitest et l'arithmétique des crans

Premier test du front. La logique des crans est de l'arithmétique pure, sans React ni DOM : c'est
la seule partie du chantier qui se teste sans harnais de composants, donc la seule à écrire en TDD.

**Files:**
- Modify: `frontend/package.json` (dépendance + scripts)
- Create: `frontend/src/ui/sheetCrans.ts`
- Test: `frontend/src/ui/sheetCrans.test.ts`

**Interfaces:**
- Consumes: rien.
- Produces:
  - `type Cran = "apercu" | "moitie" | "plein"`
  - `PEEK_HEIGHT: number` (= 96)
  - `cranHeight(cran: Cran, viewportHeight: number): number`
  - `nextCran(cran: Cran): Cran` — cyclique
  - `snap(heightPx: number, velocityPxPerMs: number, viewportHeight: number, from: Cran): Cran`
  - `mapPadding(cran: Cran, viewportHeight: number): number`

- [ ] **Step 1: Installer Vitest et déclarer les scripts**

Vitest 2 est le majeur qui accompagne Vite 5 — ne pas laisser npm résoudre un majeur plus récent.

```bash
cd frontend
npm install --save-dev vitest@^2.1.8
```

Puis dans `frontend/package.json`, ajouter aux `scripts` :

```json
    "test": "vitest run",
    "test:watch": "vitest"
```

Aucun fichier de configuration Vitest : les fonctions testées sont pures, l'environnement `node`
par défaut suffit (pas de jsdom). Les tests importent explicitement depuis `vitest`, donc aucun
réglage `globals` ni `types` dans `tsconfig.json`.

- [ ] **Step 2: Écrire le test qui échoue**

Créer `frontend/src/ui/sheetCrans.test.ts` :

```ts
import { describe, expect, it } from "vitest";
import { cranHeight, mapPadding, nextCran, PEEK_HEIGHT, snap } from "./sheetCrans";

// 844 = hauteur d'un iPhone 12/13 en portrait, la cible principale du chantier.
const IPHONE = 844;

describe("cranHeight", () => {
  it("réduit l'aperçu à la poignée et au résumé", () => {
    expect(cranHeight("apercu", IPHONE)).toBe(PEEK_HEIGHT);
  });

  it("donne la moitié et 90 % de la hauteur aux deux autres crans", () => {
    expect(cranHeight("moitie", IPHONE)).toBe(422);
    expect(cranHeight("plein", IPHONE)).toBe(760);
  });

  it("ne descend jamais sous l'aperçu, même sur un écran très court", () => {
    // Sur 150 px de haut, 50 % vaudrait 75 px : la feuille rétrécirait en s'ouvrant.
    expect(cranHeight("moitie", 150)).toBe(PEEK_HEIGHT);
  });
});

describe("nextCran", () => {
  it("avance d'un cran et revient à l'aperçu après le plein", () => {
    expect(nextCran("apercu")).toBe("moitie");
    expect(nextCran("moitie")).toBe("plein");
    expect(nextCran("plein")).toBe("apercu");
  });
});

describe("snap", () => {
  it("retient le cran le plus proche quand le geste est lent", () => {
    expect(snap(400, 0, IPHONE, "apercu")).toBe("moitie");
    expect(snap(120, 0, IPHONE, "moitie")).toBe("apercu");
  });

  it("suit un geste vif même si la feuille a peu bougé", () => {
    // Vitesse positive = la feuille grandit (doigt vers le haut).
    expect(snap(110, 1, IPHONE, "apercu")).toBe("moitie");
    expect(snap(420, -1, IPHONE, "moitie")).toBe("apercu");
  });

  it("ne boucle pas sur un geste vif : le plein reste le plein", () => {
    // nextCran est cyclique pour le toucher ; un glissement vers le haut, non.
    expect(snap(760, 1, IPHONE, "plein")).toBe("plein");
    expect(snap(96, -1, IPHONE, "apercu")).toBe("apercu");
  });
});

describe("mapPadding", () => {
  it("suit la hauteur de la feuille tant qu'elle reste modeste", () => {
    expect(mapPadding("apercu", IPHONE)).toBe(PEEK_HEIGHT);
  });

  it("plafonne à 45 % : au-delà la géométrie de caméra devient absurde", () => {
    expect(mapPadding("plein", IPHONE)).toBe(380);
  });
});
```

- [ ] **Step 3: Lancer le test pour vérifier qu'il échoue**

Run: `cd frontend && npm test`
Expected: FAIL — `Failed to resolve import "./sheetCrans"`.

- [ ] **Step 4: Écrire l'implémentation**

Créer `frontend/src/ui/sheetCrans.ts` :

```ts
export type Cran = "apercu" | "moitie" | "plein";

/** Poignée (44 px, seuil tactile) + ligne de résumé (52 px) : ce qui reste visible replié. */
export const PEEK_HEIGHT = 96;

/** Du plus bas au plus haut : l'ordre porte la notion de « cran suivant ». */
const ORDER: Cran[] = ["apercu", "moitie", "plein"];

const RATIO: Record<Cran, number> = { apercu: 0, moitie: 0.5, plein: 0.9 };

/** Au-delà de cette vitesse (px/ms), le geste décide du sens et la position ne compte plus. */
const FLICK = 0.5;

/** Part maximale de la hauteur retirée à la caméra (cf. mapPadding). */
const MAX_PADDING_RATIO = 0.45;

export function cranHeight(cran: Cran, viewportHeight: number): number {
  // Le plancher vaut pour tous les crans : sur un écran très court, 50 % passerait sous la
  // poignée et la feuille rétrécirait en s'ouvrant.
  return Math.max(PEEK_HEIGHT, Math.round(viewportHeight * RATIO[cran]));
}

export function nextCran(cran: Cran): Cran {
  return ORDER[(ORDER.indexOf(cran) + 1) % ORDER.length];
}

/** Voisin borné aux extrémités — contrairement à `nextCran`, qui boucle. */
function neighbour(cran: Cran, direction: 1 | -1): Cran {
  const index = ORDER.indexOf(cran) + direction;
  return ORDER[Math.min(ORDER.length - 1, Math.max(0, index))];
}

export function snap(
  heightPx: number,
  velocityPxPerMs: number,
  viewportHeight: number,
  from: Cran,
): Cran {
  if (velocityPxPerMs > FLICK) {
    return neighbour(from, 1);
  }
  if (velocityPxPerMs < -FLICK) {
    return neighbour(from, -1);
  }
  const distance = (cran: Cran) => Math.abs(cranHeight(cran, viewportHeight) - heightPx);
  return ORDER.reduce((best, cran) => (distance(cran) < distance(best) ? cran : best));
}

/**
 * Hauteur dont la caméra doit se décaler pour que ses recentrages tombent au-dessus de la
 * feuille. Plafonnée : au cran plein, retirer 90 % de la hauteur ne laisserait à MapLibre
 * qu'une bande de quelques dizaines de pixels pour calculer un centre.
 */
export function mapPadding(cran: Cran, viewportHeight: number): number {
  return Math.min(cranHeight(cran, viewportHeight), Math.round(viewportHeight * MAX_PADDING_RATIO));
}
```

- [ ] **Step 5: Lancer les tests pour vérifier qu'ils passent**

Run: `cd frontend && npm test`
Expected: PASS — 8 tests.

- [ ] **Step 6: Vérifier que le build reste vert**

`tsc -b` compile aussi `src/**/*.test.ts` (le `tsconfig.json` inclut tout `src`), donc le build
prouve que les types de test tiennent.

Run: `cd frontend && npm run build`
Expected: succès, aucun avertissement TypeScript.

- [ ] **Step 7: Commit**

```bash
git add frontend/package.json frontend/package-lock.json frontend/src/ui/sheetCrans.ts frontend/src/ui/sheetCrans.test.ts
git commit -m "test(front): UX-2 — Vitest et l'arithmétique des crans de la feuille"
```

---

### Task 2: Sortir le placement des panneaux (desktop inchangé)

Geste central du chantier. Aucun rendu ne doit changer : c'est ce qui rend la tâche vérifiable.
Les trois panneaux recopient aujourd'hui la même chrome (fond blanc, rayon, ombre,
`position: absolute`) ; elle part dans `FloatingCard`, leur titre dans `PanelHeader`, et le
sommaire du `LinePicker` dans `NetworkSummary` (sans quoi la feuille de la tâche 3 afficherait
deux fois le compteur de trains).

**Files:**
- Create: `frontend/src/ui/FloatingCard.tsx`
- Create: `frontend/src/ui/PanelHeader.tsx`
- Create: `frontend/src/ui/NetworkSummary.tsx`
- Create: `frontend/src/ui/lineOrder.ts`
- Test: `frontend/src/ui/lineOrder.test.ts`
- Modify: `frontend/src/ui/StopPanel.tsx` (retirer chrome + titre + ✕)
- Modify: `frontend/src/ui/VehiclePanel.tsx` (idem)
- Modify: `frontend/src/ui/LinePicker.tsx` (retirer chrome + sommaire + tri)
- Modify: `frontend/src/App.tsx` (composer les conteneurs)

**Interfaces:**
- Consumes: rien de la tâche 1.
- Produces:
  - `FloatingCard({ anchor: "top-right" | "bottom-left", style?: CSSProperties, children })`
  - `PanelHeader({ title: string, onClose: () => void })`
  - `NetworkSummary({ total, disruptedCount, disruptionsOpen, onToggleDisruptions, canShowAll, onShowAll, stale })`
  - `humanOrder(a: NetworkLine, b: NetworkLine): number` depuis `lineOrder.ts`
  - `StopPanel({ data, onSelectTrain?, onSelectLine? })` — **plus de `onClose`**
  - `VehiclePanel({ vehicle, following?, onFollow? })` — **plus de `onClose`**
  - `LinePicker({ lines, disrupted, counts, disruptions, disruptionsOpen, visible, onToggle, asOf })`

- [ ] **Step 1: Écrire le test qui échoue pour `humanOrder`**

`humanOrder` quitte `LinePicker` parce que `App` doit trier avant de dériver la liste des lignes
perturbées. Il devient donc testable.

Créer `frontend/src/ui/lineOrder.test.ts` :

```ts
import { describe, expect, it } from "vitest";
import type { NetworkLine } from "../api/types";
import { humanOrder } from "./lineOrder";

const line = (id: string): NetworkLine =>
  ({ id, shortName: id, color: "#000" }) as NetworkLine;

describe("humanOrder", () => {
  it("classe 3 avant 14, contrairement à l'ordre alphabétique", () => {
    const sorted = [line("14"), line("3"), line("1")].sort(humanOrder).map((l) => l.id);
    expect(sorted).toEqual(["1", "3", "14"]);
  });

  it("place les lignes bis juste après leur numéro", () => {
    const sorted = [line("7b"), line("7"), line("3b"), line("3")].sort(humanOrder).map((l) => l.id);
    expect(sorted).toEqual(["3", "3b", "7", "7b"]);
  });
});
```

- [ ] **Step 2: Lancer le test pour vérifier qu'il échoue**

Run: `cd frontend && npm test`
Expected: FAIL — `Failed to resolve import "./lineOrder"`.

- [ ] **Step 3: Créer `lineOrder.ts` et le vider de `LinePicker`**

Créer `frontend/src/ui/lineOrder.ts` — le corps est déplacé tel quel depuis `LinePicker.tsx` :

```ts
import type { NetworkLine } from "../api/types";

/** Ordre humain : 1, 2, 3, 3b, 4… 14 — et non l'ordre alphabétique, qui mettrait 14 avant 3. */
export function humanOrder(a: NetworkLine, b: NetworkLine): number {
  const num = (id: string) => Number.parseInt(id, 10) || Number.MAX_SAFE_INTEGER;
  return num(a.id) - num(b.id) || a.id.localeCompare(b.id);
}
```

Puis supprimer la fonction `humanOrder` de `frontend/src/ui/LinePicker.tsx` (lignes 23-27).

Run: `cd frontend && npm test`
Expected: PASS — 10 tests.

- [ ] **Step 4: Créer `FloatingCard`**

Le paramètre `style` est un échappatoire assumé : le `LinePicker` a son propre `padding`, sa
`font` et sa largeur maximale, que la chrome partagée n'a pas à connaître.

Créer `frontend/src/ui/FloatingCard.tsx` :

```tsx
import type { CSSProperties, ReactNode } from "react";

const ANCHORS: Record<Anchor, CSSProperties> = {
  "top-right": { top: 12, right: 12 },
  "bottom-left": { bottom: 12, left: 12 },
};

type Anchor = "top-right" | "bottom-left";

interface Props {
  anchor: Anchor;
  /** Ce que ce panneau-là fait différemment (padding, font, largeur). */
  style?: CSSProperties;
  children: ReactNode;
}

/**
 * Carte flottante du rendu large. Existe pour que les panneaux ignorent où ils sont posés : sur
 * écran étroit c'est `Sheet` qui les accueille, sans qu'ils aient à le savoir.
 */
export function FloatingCard({ anchor, style, children }: Props) {
  return (
    <div
      style={{
        position: "absolute",
        ...ANCHORS[anchor],
        padding: 16,
        background: "#fff",
        borderRadius: 8,
        boxShadow: "0 2px 12px rgba(0,0,0,.2)",
        font: "14px sans-serif",
        ...style,
      }}
    >
      {children}
    </div>
  );
}
```

- [ ] **Step 5: Créer `PanelHeader`**

`alignItems: "flex-start"` reproduit le comportement du `float: right` qu'il remplace : le ✕
reste aligné sur la première ligne d'un titre qui passe à la ligne.

Créer `frontend/src/ui/PanelHeader.tsx` :

```tsx
interface Props {
  title: string;
  onClose: () => void;
}

/** Titre et fermeture d'une fiche, communs à la carte flottante et à la feuille. */
export function PanelHeader({ title, onClose }: Props) {
  return (
    <div style={{ display: "flex", alignItems: "flex-start", gap: 8, margin: "0 0 8px" }}>
      <h3 style={{ margin: 0, font: "600 15px sans-serif", flex: 1, minWidth: 0 }}>{title}</h3>
      <button
        onClick={onClose}
        aria-label="Fermer"
        style={{
          flex: "0 0 auto",
          border: "none",
          background: "none",
          cursor: "pointer",
          fontSize: 20,
          lineHeight: 1,
          padding: 4,
          minWidth: "var(--tap)",
          minHeight: "var(--tap)",
        }}
      >
        ✕
      </button>
    </div>
  );
}
```

`--tap` n'existe pas encore : une variable CSS non définie laisse `min-width`/`min-height` à leur
valeur initiale, donc aucun effet sur desktop. Elle est déclarée à la tâche 3.

- [ ] **Step 6: Créer `NetworkSummary`**

Contenu déplacé depuis `LinePicker.tsx` : l'en-tête (lignes 51-76) et l'alerte de gel
(lignes 146-150).

Créer `frontend/src/ui/NetworkSummary.tsx` :

```tsx
interface Props {
  total: number;
  disruptedCount: number;
  disruptionsOpen: boolean;
  onToggleDisruptions: () => void;
  /** Un sous-ensemble de lignes est isolé : « tout afficher » a du sens. */
  canShowAll: boolean;
  onShowAll: () => void;
  /** Dernier poll `/vehicles` en échec : ce qui est affiché ne bouge plus. */
  stale: boolean;
}

/**
 * Résumé de l'état du réseau. Sur écran large, ligne de tête du sélecteur ; sur écran étroit, le
 * seul contenu visible quand la feuille est repliée — d'où son extraction hors du `LinePicker`.
 */
export function NetworkSummary({
  total, disruptedCount, disruptionsOpen, onToggleDisruptions, canShowAll, onShowAll, stale,
}: Props) {
  return (
    <>
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", gap: 8 }}>
        <b>{total} trains en circulation</b>
        {canShowAll && (
          <button
            onClick={onShowAll}
            style={{
              border: "none", background: "none", color: "#1d4ed8", cursor: "pointer",
              font: "inherit", minHeight: "var(--tap)",
            }}
          >
            tout afficher
          </button>
        )}
      </div>
      {disruptedCount > 0 && (
        <button
          onClick={onToggleDisruptions}
          style={{
            marginTop: 6, padding: 0, border: "none", background: "none", cursor: "pointer",
            font: "inherit", color: "#b45309", textAlign: "left", minHeight: "var(--tap)",
          }}
          aria-expanded={disruptionsOpen}
        >
          {disruptedCount === 1 ? "1 ligne perturbée" : `${disruptedCount} lignes perturbées`}
          {disruptionsOpen ? " ▾" : " ▸"}
        </button>
      )}
      {stale && (
        <div style={{ color: "#b45309", marginTop: 6 }} role="status">
          ⚠ Positions plus mises à jour — la connexion au service est interrompue.
        </div>
      )}
    </>
  );
}
```

- [ ] **Step 7: Réduire `LinePicker` à son contenu**

Remplacer intégralement `frontend/src/ui/LinePicker.tsx` par :

```tsx
import type { LineDisruptions, NetworkLine } from "../api/types";
import { severityStyle } from "./severity";
import { DisruptionRow } from "./DisruptionRow";

interface Props {
  /** Déjà triées dans l'ordre humain par `App`. */
  lines: NetworkLine[];
  /** Sous-ensemble de `lines` ayant une perturbation en cours, même ordre. */
  disrupted: NetworkLine[];
  counts: Map<string, number>;
  /** Perturbations en cours par ligne ; une ligne absente n'a rien à signaler. */
  disruptions: Map<string, LineDisruptions>;
  /** Liste des perturbations ouverte. Piloté par App : la carte s'en sert pour l'emphase. */
  disruptionsOpen: boolean;
  /** null = toutes les lignes visibles. */
  visible: Set<string> | null;
  /** Horodatage du dernier snapshot servi par `/vehicles` ; null avant le premier poll. */
  asOf: string | null;
  onToggle: (lineId: string) => void;
}

export function LinePicker({
  lines, disrupted, counts, disruptions, disruptionsOpen, visible, asOf, onToggle,
}: Props) {
  return (
    <>
      {disruptionsOpen && disrupted.length > 0 && (
        <ul style={{ margin: "6px 0 0", padding: 0, listStyle: "none" }}>
          {disrupted.flatMap((line) =>
            disruptions.get(line.id)!.items.map((item, index) => (
              <DisruptionRow
                key={`${line.id}-${index}`}
                item={item}
                leading={
                  <span
                    style={{
                      flex: "0 0 auto", width: 18, height: 18, borderRadius: "50%",
                      background: line.color, color: "#fff", font: "bold 11px sans-serif",
                      display: "flex", alignItems: "center", justifyContent: "center",
                    }}
                  >
                    {line.shortName}
                  </span>
                }
              />
            )),
          )}
        </ul>
      )}
      <div style={{ display: "flex", flexWrap: "wrap", gap: 6, marginTop: 8 }}>
        {lines.map((line) => {
          const shown = !visible || visible.has(line.id);
          const disruption = disruptions.get(line.id);
          const style = disruption ? severityStyle(disruption.severity) : null;
          return (
            <button
              key={line.id}
              onClick={() => onToggle(line.id)}
              title={disruption
                ? `Ligne ${line.shortName} — ${style!.label} : ${disruption.items.map((i) => i.title).join(" · ")}`
                : `${counts.get(line.id) ?? 0} train(s) sur la ligne ${line.shortName}`}
              style={{
                display: "flex",
                alignItems: "center",
                gap: 4,
                padding: "2px 6px",
                border: `1px solid ${style ? style.color : "#ddd"}`,
                borderRadius: 12,
                background: shown ? "#fff" : "#f3f3f3",
                opacity: shown ? 1 : 0.45,
                cursor: "pointer",
                font: "12px sans-serif",
                minHeight: "var(--tap)",
              }}
            >
              <span
                style={{
                  width: 16,
                  height: 16,
                  borderRadius: "50%",
                  background: line.color,
                  color: "#fff",
                  font: "bold 10px sans-serif",
                  display: "flex",
                  alignItems: "center",
                  justifyContent: "center",
                }}
              >
                {line.shortName}
              </span>
              {counts.get(line.id) ?? 0}
              {style && <span style={{ color: style.color, fontWeight: 700 }}>{style.glyph}</span>}
            </button>
          );
        })}
      </div>
      <div style={{ color: "#666", marginTop: 6 }}>
        Position estimée (pas de GPS en métro). Les trains atténués ont un placement approximatif.
        {/* Date de mise à jour de la donnée : l'article 5.7 de la Licence Mobilité (« neutralité
            et loyauté ») interdit d'induire en erreur sur le contenu ET sur sa date de mise à
            jour. Le disclaimer ci-dessus couvre la nature estimée, cette ligne la fraîcheur. */}
        {asOf && ` Données IDFM du ${new Date(asOf).toLocaleTimeString("fr-FR")}.`}
      </div>
    </>
  );
}
```

- [ ] **Step 8: Retirer chrome et titre de `StopPanel`**

Dans `frontend/src/ui/StopPanel.tsx` :

1. Retirer `onClose` de `Props` et de la signature, et passer `data: DeparturesResponse` (plus
   `| null`) — `App` ne le monte plus que lorsqu'il y a une station.
2. Supprimer le garde `if (!data) { return null; }`.
3. Remplacer le `<div style={{ position: "absolute", … }}>` englobant, le `<button>` de fermeture
   et le `<h3>` par un simple fragment `<>` … `</>`. Le premier enfant devient le bloc des
   perturbations, inchangé.
4. Fermer par `</>` au lieu de `</div>`.

Le résultat commence ainsi :

```tsx
interface Props {
  data: DeparturesResponse;
  onSelectTrain?: (journeyRef: string) => void;
  onSelectLine?: (lineId: string) => void;
}

export function StopPanel({ data, onSelectTrain, onSelectLine }: Props) {
  // Le panneau peut vieillir entre deux rafraîchissements : on masque les passages déjà partis
  // et les groupes qui n'ont plus rien à venir.
  const now = Date.now();
  const lines = data.lines
    .map((line) => ({
```

…et le `return` devient :

```tsx
  return (
    <>
      {/* Perturbations visant les quais de cette station : c'est ce que l'anneau sur la carte a
          promis d'expliquer. Placé avant les passages — savoir que l'arrêt n'est pas desservi
          change la lecture des horaires qui suivent. */}
      {data.disruptions.length > 0 && (
```

Ajouter enfin `minHeight: "var(--tap)"` au bouton d'un passage (celui qui porte `title="Suivre ce
métro"`) et au bouton de la pastille de ligne (celui qui porte `N'afficher que la ligne …`).

- [ ] **Step 9: Retirer chrome et titre de `VehiclePanel`**

Dans `frontend/src/ui/VehiclePanel.tsx` : mêmes trois retraits (`onClose`, chrome, `<h3>`),
`vehicle: Vehicle` non nullable, garde supprimé, fragment à la place du `div`. Ajouter
`minHeight: "var(--tap)"` au bouton « Suivre ».

- [ ] **Step 10: Composer les conteneurs dans `App`**

Dans `frontend/src/App.tsx` :

1. Remplacer les imports de panneaux par :

```tsx
import { VehiclePanel } from "./ui/VehiclePanel";
import { StopPanel } from "./ui/StopPanel";
import { LinePicker } from "./ui/LinePicker";
import { NetworkStatus } from "./ui/NetworkStatus";
import { NetworkSummary } from "./ui/NetworkSummary";
import { FloatingCard } from "./ui/FloatingCard";
import { PanelHeader } from "./ui/PanelHeader";
import { humanOrder } from "./ui/lineOrder";
```

2. Juste avant le `return`, dériver les listes et les éléments réutilisés par les deux mises en
   page (la tâche 3 ajoutera la seconde) :

```tsx
  // Trié une fois ici : `App` a besoin de l'ordre pour dériver les lignes perturbées, et le
  // sélecteur pour ses pastilles. Deux tris divergeraient.
  const orderedLines = useMemo(
    () => [...(network?.lines ?? [])].sort(humanOrder),
    [network],
  );
  const disrupted = useMemo(
    () => orderedLines.filter((line) => disruptions.byLine.has(line.id)),
    [orderedLines, disruptions.byLine],
  );
  const total = [...counts.values()].reduce((sum, n) => sum + n, 0);

  const networkSummary = (
    <NetworkSummary
      total={total}
      disruptedCount={disrupted.length}
      disruptionsOpen={disruptionsOpen}
      onToggleDisruptions={() => setDisruptionsOpen((open) => !open)}
      canShowAll={visibleLines !== null}
      onShowAll={() => setVisibleLines(null)}
      stale={stale}
    />
  );
  const linePicker = (
    <LinePicker
      lines={orderedLines}
      disrupted={disrupted}
      counts={counts}
      disruptions={disruptions.byLine}
      disruptionsOpen={disruptionsOpen}
      visible={visibleLines}
      asOf={asOf}
      onToggle={toggleLine}
    />
  );
  // Une seule fiche existe à la fois : `App` vide la sélection train à l'ouverture d'une station
  // et l'inverse. C'est ce qui permet à la feuille de la tâche 3 de n'avoir qu'un contenu.
  const ficheHeader = station
    ? <PanelHeader title={station.stationName} onClose={closeStation} />
    : selected
      ? <PanelHeader title={`→ ${selected.headsign}`} onClose={clearSelection} />
      : null;
  const ficheBody = station
    ? (
      <StopPanel
        data={station}
        onSelectTrain={followTrainFromPanel}
        // Isolement inconditionnel : même intention qu'un clic dans LinePicker, quel que
        // soit visibleLines courant. La station reste affichée par construction : elle est
        // desservie par lineId (c'est sa propre pastille), donc son filtre dans useNetwork
        // (station.lineIds.some(id => visibleLines.has(id))) la garde visible.
        onSelectLine={(lineId) => setVisibleLines(new Set([lineId]))}
      />
    )
    : selected
      ? (
        <VehiclePanel
          vehicle={selected}
          following={follow}
          onFollow={() => setFollow((f) => !f)}
        />
      )
      : null;
```

3. Remplacer le corps du `return` par :

```tsx
  return (
    <>
      <div ref={container} style={{ position: "absolute", inset: 0 }} />
      <NetworkStatus status={status} />
      {ficheHeader && (
        <FloatingCard anchor="top-right" style={{ width: 280, maxHeight: "70dvh", overflowY: "auto" }}>
          {ficheHeader}
          {ficheBody}
        </FloatingCard>
      )}
      <FloatingCard
        anchor="bottom-left"
        style={{ padding: "10px 12px", font: "13px sans-serif", maxWidth: 300 }}
      >
        {networkSummary}
        {linePicker}
      </FloatingCard>
    </>
  );
```

`70dvh` remplace ici le `70vh` de l'ancien `StopPanel` (correctif prévu en section 10 de la spec).

- [ ] **Step 11: Vérifier le build et les tests**

Run: `cd frontend && npm test && npm run build`
Expected: PASS (10 tests) puis build réussi.

- [ ] **Step 12: Contrôle visuel desktop**

Ouvrir l'application sur un écran large et comparer avec l'état précédent : compteur de trains,
« tout afficher », « N lignes perturbées » dépliable, pastilles, pied de licence, fiche station
(titre + ✕ + perturbations + passages), fiche train. **Rien ne doit avoir bougé** hors le
positionnement du ✕ sur un titre à deux lignes.

Signaler à l'utilisateur avant de continuer ; ne pas démarrer le serveur soi-même.

- [ ] **Step 13: Commit**

```bash
git add frontend/src/ui frontend/src/App.tsx
git commit -m "refactor(front): UX-2 — les panneaux ne savent plus où ils vivent"
```

---

### Task 3: La feuille

**Files:**
- Create: `frontend/src/index.css`
- Create: `frontend/src/ui/useViewport.ts`
- Create: `frontend/src/ui/Sheet.tsx`
- Modify: `frontend/index.html` (viewport-fit)
- Modify: `frontend/src/main.tsx` (importer le CSS)
- Modify: `frontend/src/App.tsx` (brancher les deux mises en page)

**Interfaces:**
- Consumes: `Cran`, `cranHeight`, `nextCran`, `snap`, `PEEK_HEIGHT` (tâche 1) ;
  `networkSummary`, `linePicker`, `ficheHeader`, `ficheBody` (tâche 2).
- Produces:
  - `NARROW_MAX_WIDTH: number` (= 720), `useIsNarrow(): boolean`, `useViewportHeight(): number`
  - `Sheet({ cran, onCranChange, viewportHeight, summary, children, label })`

- [ ] **Step 1: Déclarer la zone sûre dans `index.html`**

Sans `viewport-fit=cover`, `env(safe-area-inset-bottom)` vaut 0 sur iPhone et la feuille passe
sous la barre d'accueil.

Dans `frontend/index.html`, remplacer la balise viewport par :

```html
    <meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover" />
```

- [ ] **Step 2: Créer `src/index.css` et l'importer**

Créer `frontend/src/index.css` :

```css
/* Premier CSS du projet : tout le reste est en style inline. N'accueille donc que ce que le
   style inline ne sait pas exprimer — media query, env(), gestes tactiles. */

html,
body {
  margin: 0;
  height: 100%;
  /* Sans ça, tirer la feuille vers le bas déclenche le rechargement-par-traction de Chrome
     Android au lieu de la replier. */
  overscroll-behavior: none;
}

:root {
  /* Hauteur minimale d'une cible tactile. 0 sur desktop : sans effet sur le rendu existant. */
  --tap: 0px;
}

/* Doit rester synchronisé avec NARROW_MAX_WIDTH dans src/ui/useViewport.ts. */
@media (max-width: 720px) {
  :root {
    --tap: 44px;
  }
}
```

Dans `frontend/src/main.tsx`, ajouter l'import **après** celui de MapLibre pour que nos règles
gagnent en cas d'égalité de spécificité :

```tsx
import "maplibre-gl/dist/maplibre-gl.css";
import "./index.css";
```

- [ ] **Step 3: Créer `useViewport.ts`**

```ts
import { useEffect, useState } from "react";

/** Doit rester synchronisé avec la media query de `src/index.css` (variable `--tap`). */
export const NARROW_MAX_WIDTH = 720;

// Largeur seule : un téléphone en paysage (844 × 390) garde les cartes flottantes, une feuille
// sur 390 px de haut serait pire que le mal.
const NARROW = `(max-width: ${NARROW_MAX_WIDTH}px)`;

export function useIsNarrow(): boolean {
  const [narrow, setNarrow] = useState(() => window.matchMedia(NARROW).matches);
  useEffect(() => {
    const query = window.matchMedia(NARROW);
    const onChange = (event: MediaQueryListEvent) => setNarrow(event.matches);
    query.addEventListener("change", onChange);
    // Relu ici : la largeur peut avoir changé entre le premier rendu et cet effet.
    setNarrow(query.matches);
    return () => query.removeEventListener("change", onChange);
  }, []);
  return narrow;
}

/**
 * Hauteur du viewport en pixels — la feuille se dimensionne en px, pas en `dvh`, parce qu'un
 * glissement doit suivre le doigt au pixel. `innerHeight` suit la barre d'outils mobile qui se
 * replie, comme `dvh` le ferait en CSS.
 */
export function useViewportHeight(): number {
  const [height, setHeight] = useState(() => window.innerHeight);
  useEffect(() => {
    const onResize = () => setHeight(window.innerHeight);
    window.addEventListener("resize", onResize);
    onResize();
    return () => window.removeEventListener("resize", onResize);
  }, []);
  return height;
}
```

- [ ] **Step 4: Créer `Sheet.tsx`**

Trois points à ne pas simplifier, tous justifiés en commentaire dans le code :

- Le glissement ne part **que de la poignée**, jamais du corps : supprime tout arbitrage entre
  glisser la feuille et défiler son contenu.
- La poignée est un `<button>` **sans le résumé dedans** : le résumé contient ses propres
  boutons, et un bouton dans un bouton est invalide et casse les clics.
- Un `click` suit un glissement comme un simple toucher ; sans le drapeau `moved`, chaque
  glissement avancerait aussi d'un cran.

```tsx
import { useRef, useState, type PointerEvent as ReactPointerEvent, type ReactNode } from "react";
import { cranHeight, nextCran, snap, type Cran } from "./sheetCrans";

interface Props {
  cran: Cran;
  onCranChange: (cran: Cran) => void;
  viewportHeight: number;
  /** Toujours visible, même repliée : posé sous la poignée, hors de la zone qui défile. */
  summary: ReactNode;
  children: ReactNode;
  label: string;
}

/**
 * Feuille repliable du rendu étroit. Coquille présentationnelle : elle ne sait rien de son
 * contenu, et `App` détient le cran (la carte en dérive son padding de caméra).
 */
export function Sheet({ cran, onCranChange, viewportHeight, summary, children, label }: Props) {
  const settled = cranHeight(cran, viewportHeight);
  // Non nul seulement pendant un glissement : sert aussi à couper la transition.
  const [dragged, setDragged] = useState<number | null>(null);
  // Tout l'état du geste vit dans un ref, jamais dans `dragged` : les événements pointeur
  // arrivent plus vite que les rendus, et lire un état pas encore commité perdrait le premier
  // mouvement — ou ferait atterrir le lâcher sur une hauteur périmée.
  const gesture = useRef({
    active: false, startY: 0, startHeight: 0,
    lastY: 0, lastT: 0, velocity: 0, height: 0, moved: false,
  });
  const height = dragged ?? settled;

  const onPointerDown = (event: ReactPointerEvent<HTMLButtonElement>) => {
    event.currentTarget.setPointerCapture(event.pointerId);
    gesture.current = {
      active: true, startY: event.clientY, startHeight: settled,
      lastY: event.clientY, lastT: event.timeStamp, velocity: 0, height: settled, moved: false,
    };
    setDragged(settled);
  };

  const onPointerMove = (event: ReactPointerEvent<HTMLButtonElement>) => {
    const g = gesture.current;
    if (!g.active) {
      return;
    }
    const elapsed = event.timeStamp - g.lastT;
    if (elapsed > 0) {
      // Positif = la feuille grandit (doigt vers le haut), convention de `snap`.
      g.velocity = (g.lastY - event.clientY) / elapsed;
      g.lastY = event.clientY;
      g.lastT = event.timeStamp;
    }
    if (Math.abs(event.clientY - g.startY) > 6) {
      g.moved = true;
    }
    g.height = Math.max(
      cranHeight("apercu", viewportHeight),
      Math.min(cranHeight("plein", viewportHeight), g.startHeight + (g.startY - event.clientY)),
    );
    setDragged(g.height);
  };

  const onPointerUp = () => {
    const g = gesture.current;
    if (!g.active) {
      return;
    }
    g.active = false;
    setDragged(null);
    onCranChange(snap(g.height, g.velocity, viewportHeight, cran));
  };

  // Un toucher sans déplacement, ou une touche Entrée/Espace sur la poignée : cran suivant.
  const onClick = () => {
    if (!gesture.current.moved) {
      onCranChange(nextCran(cran));
    }
  };

  return (
    <section
      aria-label={label}
      style={{
        position: "fixed",
        left: 0,
        right: 0,
        bottom: 0,
        // La zone sûre s'ajoute à la hauteur du cran : sinon elle la rognerait et l'aperçu
        // perdrait sa ligne de résumé sur les iPhone récents.
        height: `calc(${height}px + env(safe-area-inset-bottom, 0px))`,
        paddingBottom: "env(safe-area-inset-bottom, 0px)",
        boxSizing: "border-box",
        display: "flex",
        flexDirection: "column",
        background: "#fff",
        borderRadius: "14px 14px 0 0",
        boxShadow: "0 -2px 16px rgba(0,0,0,.2)",
        font: "13px sans-serif",
        transition: dragged === null ? "height 220ms ease-out" : "none",
      }}
    >
      <button
        onPointerDown={onPointerDown}
        onPointerMove={onPointerMove}
        onPointerUp={onPointerUp}
        onPointerCancel={onPointerUp}
        onClick={onClick}
        aria-expanded={cran !== "apercu"}
        aria-label="Changer la hauteur du panneau"
        style={{
          flex: "0 0 auto",
          height: 44,
          border: "none",
          background: "none",
          padding: 0,
          cursor: "grab",
          // Sans ça, le navigateur traite le glissement vertical comme un défilement de page.
          touchAction: "none",
        }}
      >
        <div style={{ width: 36, height: 4, borderRadius: 2, background: "#ccc", margin: "0 auto" }} />
      </button>
      <div style={{ flex: "0 0 auto", padding: "0 12px" }}>{summary}</div>
      <div style={{ flex: "1 1 auto", overflowY: "auto", overscrollBehavior: "contain", padding: "0 12px 12px" }}>
        {children}
      </div>
    </section>
  );
}
```

- [ ] **Step 5: Brancher les deux mises en page dans `App`**

Ajouter aux imports :

```tsx
import { Sheet } from "./ui/Sheet";
import { useIsNarrow, useViewportHeight } from "./ui/useViewport";
import type { Cran } from "./ui/sheetCrans";
```

Ajouter aux états, près des autres `useState` :

```tsx
  const isNarrow = useIsNarrow();
  const viewportHeight = useViewportHeight();
  // Détenu ici, pas dans la feuille : la carte en dérive son padding de caméra (tâche 4), et
  // ouvrir une station doit pouvoir remonter la feuille.
  const [cran, setCran] = useState<Cran>("apercu");
```

Remplacer le corps du `return` (celui écrit à la tâche 2) par :

```tsx
  return (
    <>
      <div ref={container} style={{ position: "absolute", inset: 0 }} />
      <NetworkStatus status={status} />
      {isNarrow ? (
        <Sheet
          cran={cran}
          onCranChange={setCran}
          viewportHeight={viewportHeight}
          summary={ficheHeader ?? networkSummary}
          label={station || selected ? "Détail" : "État du réseau"}
        >
          {ficheBody ?? linePicker}
        </Sheet>
      ) : (
        <>
          {ficheHeader && (
            <FloatingCard anchor="top-right" style={{ width: 280, maxHeight: "70dvh", overflowY: "auto" }}>
              {ficheHeader}
              {ficheBody}
            </FloatingCard>
          )}
          <FloatingCard
            anchor="bottom-left"
            style={{ padding: "10px 12px", font: "13px sans-serif", maxWidth: 300 }}
          >
            {networkSummary}
            {linePicker}
          </FloatingCard>
        </>
      )}
    </>
  );
```

- [ ] **Step 6: Vérifier le build et les tests**

Run: `cd frontend && npm test && npm run build`
Expected: PASS puis build réussi.

- [ ] **Step 7: Contrôle visuel étroit**

Dans les outils de développement, en 390 × 844 : la feuille apparaît, l'aperçu montre le compteur
et « N lignes perturbées », la poignée se glisse et se cale sur trois crans, un toucher de poignée
avance d'un cran, « tout afficher » et « N lignes perturbées » restent cliquables sans changer le
cran, le contenu déplié défile. Vérifier ensuite en 1400 px que le rendu large est intact.

- [ ] **Step 8: Commit**

```bash
git add frontend/index.html frontend/src/index.css frontend/src/main.tsx frontend/src/ui/Sheet.tsx frontend/src/ui/useViewport.ts frontend/src/App.tsx
git commit -m "feat(front): UX-2 — une feuille repliable sous 720 px"
```

---

### Task 4: La caméra tient compte de la feuille

Sans cette tâche, toucher une station la recentre **derrière** la feuille, et un train suivi y
disparaît à chaque frame.

**Files:**
- Modify: `frontend/src/App.tsx`

**Interfaces:**
- Consumes: `mapPadding` (tâche 1), `cran` / `isNarrow` / `viewportHeight` (tâche 3).
- Produces: rien de nouveau.

- [ ] **Step 1: Poser le padding de caméra**

Ajouter l'import :

```tsx
import { mapPadding, type Cran } from "./ui/sheetCrans";
```

(remplace l'import de type seul ajouté à la tâche 3.)

Ajouter cet effet après les autres `useEffect` :

```tsx
  // La feuille flotte au-dessus d'une carte qui garde tout le viewport : rien ne rétrécit, donc
  // aucun map.resize(). Un seul padding suffit à ce que TOUS les recentrages de MapLibre — le
  // easeTo du clic station comme le jumpTo par frame du suivi — se posent au-dessus d'elle.
  useEffect(() => {
    if (!map) {
      return;
    }
    const bottom = isNarrow ? mapPadding(cran, viewportHeight) : 0;
    map.setPadding({ top: 0, right: 0, bottom, left: 0 });
  }, [map, isNarrow, cran, viewportHeight]);
```

- [ ] **Step 2: Remonter la feuille quand une fiche s'ouvre**

Dans l'effet `[map]` qui pose les écouteurs de clic, ajouter la même ligne dans `onClick`
(véhicules) et dans `onStationClick`, juste après le `setFollow` / `setSelectedStationId` :

```tsx
      // Sans ça, toucher une station feuille repliée semblerait ne rien produire. Mise à jour
      // fonctionnelle : cet écouteur est posé une fois pour toutes et ne verrait pas un `cran`
      // capturé au montage.
      setCran((current) => (current === "apercu" ? "moitie" : current));
```

- [ ] **Step 3: Vérifier le build**

Run: `cd frontend && npm run build`
Expected: succès.

- [ ] **Step 4: Contrôle visuel**

En 390 × 844 : toucher une station remonte la feuille à mi-hauteur **et** la station reste visible
au-dessus d'elle. Suivre un train : sa flèche reste dans la bande visible, jamais sous la feuille.
Glisser la feuille au cran plein puis vérifier que la carte ne part pas en vrille (le plafond de
45 % joue ici). En 1400 px, aucun décalage de caméra.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/App.tsx
git commit -m "feat(front): UX-2 — la caméra se recentre au-dessus de la feuille"
```

---

### Task 5: Attribution repliée et remontée, règle amendée

**Files:**
- Create: `frontend/src/map/attribution.ts`
- Modify: `frontend/src/map/MapView.tsx`
- Modify: `CLAUDE.md`

**Interfaces:**
- Consumes: `useIsNarrow` (tâche 3).
- Produces: `SOURCE_ATTRIBUTION: string` depuis `map/attribution.ts`.

- [ ] **Step 1: Extraire le texte de la mention**

Créer `frontend/src/map/attribution.ts` :

```ts
/**
 * OBLIGATION DE LICENCE — ne pas retirer. Les données IDFM (GTFS statique et temps réel SIRI)
 * sont sous « Licence Mobilité » : son article 5.4 impose, dès que la carte est utilisée
 * publiquement, une mention informant l'utilisateur que le contenu vient de la base initiale et
 * qu'il est soumis à la licence — avec lien vers les deux. Nommée et isolée ici pour rester
 * trouvable au lieu d'être noyée dans la construction de la carte.
 *
 * L'attribution du fond de carte (OpenFreeMap / OpenMapTiles / OpenStreetMap) est, elle,
 * fournie par la TileJSON de la source et posée automatiquement par MapLibre.
 */
export const SOURCE_ATTRIBUTION =
  "Contient des informations de " +
  '<a href="https://transport.data.gouv.fr/datasets/reseau-urbain-et-interurbain-dile-de-france-mobilites"' +
  ' target="_blank" rel="noreferrer">Réseaux urbains et interurbains d\'Île-de-France Mobilités</a>' +
  ", mises à disposition aux conditions de la " +
  '<a href="https://cloud.fabmob.io/s/eYWWJBdM3fQiFNm" target="_blank" rel="noreferrer">Licence Mobilités</a>';
```

- [ ] **Step 2: Rendre le contrôle d'attribution dépendant du seuil**

Dans `frontend/src/map/MapView.tsx` :

1. Ajouter les imports :

```tsx
import { SOURCE_ATTRIBUTION } from "./attribution";
import { useIsNarrow } from "../ui/useViewport";
```

2. Dans le constructeur de la `Map`, remplacer tout le bloc `attributionControl: { … }` par
   `attributionControl: false` : le contrôle est désormais posé par l'effet ci-dessous, parce que
   `compact` et la position se fixent à la construction du contrôle et ne se modifient pas après.

3. Ajouter, en tête de `useMap`, la lecture du seuil et un emplacement pour le contrôle :

```tsx
  const isNarrow = useIsNarrow();
  const attribution = useRef<maplibregl.AttributionControl | null>(null);
```

4. Ajouter cet effet **après** celui qui crée la carte :

```tsx
  // Replié ET remonté sous le seuil. Replier seul ne suffirait pas : le bouton « ⓘ » reste
  // ancré en bas à droite, donc SOUS la feuille même repliée — la mention deviendrait
  // inatteignable, ce qui est pire que dépliée. Les recommandations OSM tolèrent le repli sur
  // écran contraint, pas sur une carte plein écran (cf. CLAUDE.md).
  useEffect(() => {
    if (!map) {
      return;
    }
    const control = new maplibregl.AttributionControl({
      compact: isNarrow,
      customAttribution: SOURCE_ATTRIBUTION,
    });
    map.addControl(control, isNarrow ? "top-right" : "bottom-right");
    attribution.current = control;
    return () => {
      map.removeControl(control);
      attribution.current = null;
    };
  }, [map, isNarrow]);
```

- [ ] **Step 3: Amender CLAUDE.md**

Dans la section « Conventions de code », remplacer la puce sur la licence des données par :

```markdown
- **Licence des données : *Licence Mobilité*, pas ODbL.** La mention de source
  (`customAttribution` dans `map/attribution.ts`, posée par `MapView.tsx`) et le pied du
  `LinePicker` (« position estimée » + heure du snapshot) ne sont pas cosmétiques : ce sont les
  obligations des art. 5.4 et 5.7 — **ne pas les retirer**. Une seule exception, et elle est
  bornée : **sous 720 px de large**, l'attribution passe en `compact` **et** en `top-right`. Les
  recommandations OSM tolèrent le repli sur écran contraint (pas sur une carte plein écran), et
  remonter le bouton est indispensable — replié en bas à droite, il serait sous la feuille, donc
  inatteignable. Au-dessus du seuil, elle reste dépliée en bas à droite. Ce qui reste à trancher
  avant un déploiement public (partage à l'identique de la base dérivée, CGU PRIM, marques) est
  listé dans la section « Données, sources et licences » du [README](README.md).
```

- [ ] **Step 4: Vérifier le build**

Run: `cd frontend && npm run build`
Expected: succès.

- [ ] **Step 5: Contrôle visuel**

En 1400 px : la mention est dépliée en bas à droite, avec l'attribution OpenStreetMap, exactement
comme avant. En 390 px : un `ⓘ` en haut à droite, qui déplie les deux attributions au toucher, et
qui n'est pas recouvert par la feuille. Passer d'une largeur à l'autre sans recharger : le
contrôle change de forme et de coin, sans doublon.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/map/attribution.ts frontend/src/map/MapView.tsx CLAUDE.md
git commit -m "feat(front): UX-2 — attribution repliée et remontée sous 720 px"
```

---

### Task 6: Cibles tactiles et derniers débordements

Les `minHeight: "var(--tap)"` posés aux tâches 2 et 3 couvrent les panneaux ; restent le bandeau
d'état, les lignes de perturbation et la largeur du bandeau sous 384 px.

**Files:**
- Modify: `frontend/src/ui/DisruptionRow.tsx`
- Modify: `frontend/src/ui/NetworkStatus.tsx`

**Interfaces:**
- Consumes: la variable `--tap` (tâche 3).
- Produces: rien de nouveau.

- [ ] **Step 1: Cible tactile du dépliement d'un détail**

Dans `frontend/src/ui/DisruptionRow.tsx`, sur le `<button>` qui porte `aria-expanded={open}`,
ajouter à son `style` :

```tsx
            minHeight: "var(--tap)",
```

- [ ] **Step 2: Bandeau d'état à largeur fluide**

Dans `frontend/src/ui/NetworkStatus.tsx`, remplacer les trois propriétés de placement

```tsx
        left: "50%",
        transform: "translateX(-50%)",
        maxWidth: 360,
```

par

```tsx
        // `maxWidth` seul débordait sous 384 px : les bornes gauche/droite le rendent fluide,
        // `margin: auto` le garde centré au-dessus du seuil.
        left: 12,
        right: 12,
        maxWidth: 360,
        margin: "0 auto",
```

- [ ] **Step 3: Vérifier le build**

Run: `cd frontend && npm run build`
Expected: succès.

- [ ] **Step 4: Contrôle visuel des cibles**

En 360 × 640, mesurer à l'inspecteur : pastilles de ligne, ✕ de fiche, « tout afficher »,
« N lignes perturbées », dépliement d'une perturbation, bouton « Suivre », bouton d'un passage —
tous à 44 px de haut au moins. Le bandeau d'état ne dépasse pas l'écran. En 1400 px, tous
retrouvent leur hauteur d'origine (`--tap: 0px`) et le bandeau reste centré.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/ui/DisruptionRow.tsx frontend/src/ui/NetworkStatus.tsx
git commit -m "feat(front): UX-2 — cibles tactiles à 44 px et bandeau fluide"
```

---

### Task 7: Recette complète et mise à jour des documents

**Files:**
- Modify: `docs/roadmap.md`
- Modify: `CLAUDE.md` (limitations connues)
- Modify: `README.md` (section « Utilisation »)

- [ ] **Step 1: Recette manuelle sur les cinq gabarits**

Passer les cinq et noter tout écart :

| Gabarit | Attendu |
|---|---|
| 390 × 844 (iPhone 12/13) | Feuille, trois crans, station visible au-dessus, `ⓘ` en haut à droite |
| 360 × 640 (petit Android) | Idem, aucune cible sous 44 px, aucun débordement horizontal |
| 844 × 390 (paysage) | **Cartes flottantes**, pas de feuille |
| 768 × 1024 (tablette portrait) | Cartes flottantes |
| 1400 × 900 (desktop) | Identique à avant le chantier |

Sur un vrai téléphone si possible : la barre d'outils qui se replie (recalcul de
`useViewportHeight`), la zone sûre en bas, et le geste de traction vers le bas qui ne recharge pas
la page.

- [ ] **Step 2: Vérification automatique complète**

Run: `cd frontend && npm test && npm run build`
Expected: PASS (10 tests) puis build réussi.

- [ ] **Step 3: Passer UX-2 à `fait` dans la feuille de route**

Dans `docs/roadmap.md`, table « 3. UX / UI », remplacer le statut `à faire` de la ligne UX-2 par :

```
**fait** (feuille repliable unique à trois crans sous 720 px ; les panneaux ne se positionnent plus eux-mêmes — `Sheet` / `FloatingCard` ; `map.setPadding` par cran pour que les recentrages tombent au-dessus de la feuille ; cibles tactiles à 44 px via `--tap`). Vitest introduit au passage pour l'arithmétique des crans, avance sur QUA-3
```

Dans « Ordre recommandé », déplacer **UX-2** dans la ligne 1 des chantiers faits et faire de
**QUA-3** le point 3.

- [ ] **Step 4: Retirer la limitation devenue fausse**

Dans `CLAUDE.md`, section « Limitations connues », supprimer la puce « **Pas d'adaptation
mobile** » et la remplacer par :

```markdown
- **Feuille repliable sous 720 px** (largeur seule : un téléphone en paysage garde les cartes
  flottantes, une feuille sur 390 px de haut serait pire que le mal). La feuille flotte au-dessus
  d'une carte qui garde tout le viewport ; c'est `map.setPadding` qui décale les recentrages, pas
  une mise en colonne — donc jamais de `map.resize()`. Le cran `moitié` laisse du blanc sous un
  contenu court : prix assumé d'un repère stable.
```

- [ ] **Step 5: Documenter le geste dans le README**

Dans `README.md`, section « Utilisation — échelle de zoom », ajouter en fin de section :

```markdown
Sous 720 px de large, les panneaux flottants sont remplacés par une **feuille repliable** en bas
d'écran : on la tire par sa poignée, ou on la touche pour passer au cran suivant (aperçu → moitié
→ plein → aperçu). Elle porte le sélecteur de lignes par défaut ; ouvrir une station ou un train y
affiche sa fiche, et la fermer ramène le sélecteur. Sur ces largeurs, la mention de source de la
carte passe derrière un « ⓘ » en haut à droite.
```

- [ ] **Step 6: Commit**

```bash
git add docs/roadmap.md CLAUDE.md README.md
git commit -m "docs: UX-2 fait — feuille repliable, et la limitation mobile tombe"
```

---

## Auto-revue

**Couverture de la spec**, section par section :

| Section de la spec | Tâche |
|---|---|
| 1. Critères de réussite | 7 (recette sur les cinq gabarits) |
| 2. État des lieux | — (constat) |
| 3. La carte ne rétrécit jamais (`setPadding`, plafond 45 %) | 1 (`mapPadding`), 4 |
| 4. Une seule feuille, contenu remplacé | 3 (`summary`/`children` dérivés de `station`/`selected`) |
| 5. Deux mécanismes de responsive (`useIsNarrow`, `--tap`) | 3 |
| 6. Panneaux sans placement (`Sheet`, `FloatingCard`, `PanelHeader`, `NetworkSummary`) | 2, 3 |
| 7. Mécanique de la feuille (crans, glissement, toucher, zone sûre, transition) | 1, 3 |
| 8. Attribution repliée **et** remontée, CLAUDE.md amendé | 5 |
| 9. Vérification (Vitest sur les quatre fonctions, recette manuelle) | 1, 7 |
| 10. Corrigés au passage (`70dvh`, bandeau fluide, reset CSS) | 2 (`70dvh`), 3 (reset), 6 (bandeau) |
| 11. Hors périmètre | — |

**Cohérence des types** : `Cran` est produit par la tâche 1 et consommé par les tâches 3 et 4 ;
`humanOrder` par la tâche 2 et consommé par `App` ; `SOURCE_ATTRIBUTION` par la tâche 5. Les
signatures de `StopPanel`, `VehiclePanel` et `LinePicker` changent en tâche 2 et `App` est mis à
jour dans la même tâche — aucun état intermédiaire ne compile à moitié.

**Point de vigilance pour l'exécutant** : la tâche 2 ne doit rien changer visuellement. Si le
contrôle visuel du step 12 révèle un écart, c'est un défaut de déplacement, pas une amélioration à
garder.
