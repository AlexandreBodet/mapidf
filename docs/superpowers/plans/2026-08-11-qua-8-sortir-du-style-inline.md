# QUA-8 — Sortir du style inline : plan d'implémentation

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Déplacer le style des douze composants du front vers des CSS Modules colocalisés, sans
changer le rendu, en posant d'abord un filet de tests sur ce que la conversion pourrait casser.

**Architecture:** Trois couches — `index.css` (reset, tokens de rôle, garde `[hidden]`, mapping de
gravité), `ui/shared.module.css` (les deux motifs dupliqués, consommés par `composes`), et un
`X.module.css` par composant. Ce qui vient de la donnée ou d'une mesure reste dans l'attribut
`style`, réduit à deux variables CSS ; les états booléens passent en `data-*` ou réutilisent l'ARIA.

**Tech Stack:** React 19, TypeScript 6.0.3, Vite 8 (rolldown), Vitest 4 + Testing Library, jsdom 27.
CSS Modules natifs de Vite — aucune dépendance ajoutée.

**Spec:** [2026-08-11-qua-8-sortir-du-style-inline-design.md](../specs/2026-08-11-qua-8-sortir-du-style-inline-design.md)

## Global Constraints

Ces contraintes valent pour **toutes** les tâches. Elles sont mesurées, pas supposées.

- **Aucun changement de rendu.** Chaque valeur (px, teinte, poids, rayon) est reportée à
  l'identique. **Les nuances proches ne sont pas fusionnées** : `#444` et `#555` restent deux
  tokens, `#fde68a` et `#fef3c7` deux fonds.
- **Un `<button>` n'hérite pas de la police du document.** Les contrôles de formulaire ont la police
  du widget (Arial-ish selon l'UA). Tout module qui stylise un bouton **doit** poser `font: inherit`
  (fourni par `composes: linkButton`) ou `font-family: var(--font)` : un `font-size` seul changerait
  la fonte, là où le raccourci `font: "13px sans-serif"` posait la famille explicitement.
- **Une variable CSS dans un `style` React exige une assertion.** Mesuré :
  `style={{ "--sheet-height": "44px" }}` échoue en `TS2353`. La forme qui compile est
  `style={{ "--sheet-height": `${h}px` } as CSSProperties}`.
- **Aucun test n'affirme une règle CSS.** Mesuré : Vitest n'applique pas les feuilles
  (`?inline` rend `""`), donc `getComputedStyle` est aveugle aux classes. Les tests affirment des
  textes, des handlers, des rôles et des attributs — jamais une couleur ni un padding.
- **Tout masquage passe par l'attribut `hidden`**, jamais par `display: none` inline. La garde
  `[hidden] { display: none !important }` est indispensable : mesuré, la feuille de l'UA a une
  origine plus faible et n'importe quelle règle auteur l'écrase.
- **Convention d'import** : `import styles from "./X.module.css"`. Les variables locales nommées
  `style` (LinePicker, DisruptionRow) sont renommées `severity` pour éviter la collision.
- **Branche** : `qua-8-sortir-du-style-inline`, un commit par tâche.
- **Vérification de fin de tâche** : `npx vitest run` vert, puis `npx tsc -b` (le typage des
  imports de modules CSS n'apparaît qu'là) et `npm run lint` muet. Les tâches de conversion
  ajoutent `npm run build`.

## File Structure

**Créés :**

| Fichier | Responsabilité |
|---|---|
| `frontend/src/ui/shared.module.css` | noyau du bouton-lien, pastille de ligne |
| `frontend/src/ui/LineBadge.tsx` | pastille ronde d'une ligne, extraite de ses trois copies |
| `frontend/src/ui/{Sheet,SheetFooter,StaleWarning,PanelHeader,NetworkStatus,NetworkSummary,FloatingCard,VehiclePanel,DisruptionRow,LinePicker,StopPanel}.module.css` | style local d'un composant |
| `frontend/src/App.module.css` | conteneur de carte, variantes de carte flottante |
| `frontend/src/ui/{SheetFooter,StaleWarning,PanelHeader,NetworkStatus,VehiclePanel,LinePicker}.test.tsx` | filet de régression des composants non couverts |

**Modifiés :** `frontend/src/index.css` (tokens, garde, gravité), les douze `.tsx` porteurs de
style, `frontend/src/ui/severity.ts` (perd `color`), et les tests `Sheet.test.tsx`,
`DisruptionRow.test.tsx`, `StopPanel.test.tsx`, `severity.test.ts`.

---

### Task 1: Fondations — tokens, garde `[hidden]`, motifs partagés

**Files:**
- Modify: `frontend/src/index.css:13-28`
- Create: `frontend/src/ui/shared.module.css`

**Interfaces:**
- Consumes: rien.
- Produces: les tokens `--font --surface --text-muted --text-detail --text-detail-open
  --text-faint --separator --handle --border --border-subtle --surface-off --accent --warn
  --amber-bg-strong --amber-bg-soft --amber-text --red-bg --red-text --sev-bloquante
  --sev-perturbee --sev-information --sev-inconnue --shadow-card --shadow-sheet`, la variable
  `--sev` posée par `[data-severity]`, et les classes `linkButton` / `lineBadge` de
  `shared.module.css`.

- [ ] **Step 1: Étendre `index.css`**

Remplacer le bloc `:root` existant et ajouter à la suite (le reset `html, body` et la media query
`--tap` restent inchangés, sauf l'ajout de `font-family` sur `body`) :

```css
html,
body {
  margin: 0;
  height: 100%;
  /* Sans ça, tirer la feuille vers le bas déclenche le rechargement-par-traction de Chrome
     Android au lieu de la replier. */
  overscroll-behavior: none;
}

/* Posée ici, la famille se diffuse par héritage : l'attribut `style` obligeait chaque bloc à la
   répéter. Ne couvre PAS les boutons, qui gardent la police du widget tant qu'ils ne posent pas
   `font: inherit` ou `font-family` eux-mêmes. */
body {
  font-family: var(--font);
}

:root {
  /* Hauteur minimale d'une cible tactile. 0 sur desktop : sans effet sur le rendu existant. */
  --tap: 0px;
  /* Zone sûre basse (barre d'accueil des iPhone). Nommée ici plutôt qu'écrite en ligne dans la
     feuille : `env()` vaut 0 sur la plupart des Android, ce qui rend le cas à encoche
     intestable. La forcer depuis les outils de développement (`--safe-bottom: 34px` sur
     `:root`) rejoue ce cas sans appareil Apple. */
  --safe-bottom: env(safe-area-inset-bottom, 0px);

  --font: sans-serif;

  /* Surfaces, texte, filets. Les nuances proches sont volontairement distinctes : les fusionner
     changerait le rendu, ce qu'interdit QUA-8. Aucune couleur de texte principale n'est définie —
     le projet n'en posait pas, tout hérite du défaut du navigateur (UX-4 en ajoutera une). */
  --surface: #fff;
  --surface-off: #f3f3f3;      /* fond d'une pastille de ligne masquée */
  --text-muted: #666;
  --text-detail: #444;         /* corps du bandeau d'état, titre sans détail */
  --text-detail-open: #555;    /* détail déplié d'une perturbation */
  --text-faint: #999;          /* heure sur la poignée de la feuille */
  --separator: #bbb;           /* point entre deux horaires */
  --handle: #ccc;              /* barre de la poignée */
  --border: #ddd;              /* contour d'une pastille sans perturbation */
  --border-subtle: #eee;       /* filet entre deux perturbations */

  --accent: #1d4ed8;
  --warn: #b45309;
  --amber-bg-strong: #fde68a;  /* badge « retardé » */
  --amber-bg-soft: #fef3c7;    /* encart « position approximative » */
  --amber-text: #92400e;
  --red-bg: #fecaca;           /* badge « supprimé » */
  --red-text: #991b1b;

  --sev-bloquante: #b91c1c;
  --sev-perturbee: var(--warn);
  --sev-information: var(--accent);
  --sev-inconnue: #6b7280;

  --shadow-card: 0 2px 12px rgba(0, 0, 0, .2);
  --shadow-sheet: 0 -2px 16px rgba(0, 0, 0, .2);
}

/* Gravité d'une perturbation : l'élément porteur déclare `data-severity`, la couleur descend par
   `--sev`. Global plutôt que dupliqué dans chaque module — les quatre valeurs sont les mêmes
   partout (sélecteur de lignes, fiche station), et `severity.ts` ne garde que glyphe et libellé.
   Le défaut couvre le repli d'une gravité que le flux aurait inventée. */
[data-severity] { --sev: var(--sev-inconnue); }
[data-severity="BLOQUANTE"] { --sev: var(--sev-bloquante); }
[data-severity="PERTURBEE"] { --sev: var(--sev-perturbee); }
[data-severity="INFORMATION"] { --sev: var(--sev-information); }

/* `[hidden]` n'appartient qu'à la feuille de l'UA, d'origine plus faible que la nôtre : mesuré,
   un `display: flex` posé par une classe l'écrase et déplierait la feuille en silence. */
[hidden] { display: none !important; }

/* Doit rester synchronisé avec NARROW_MAX_WIDTH dans src/ui/useViewport.ts. */
@media (max-width: 720px) {
  :root {
    --tap: 44px;
  }
}
```

- [ ] **Step 2: Créer `ui/shared.module.css`**

```css
/* Noyau commun aux cinq boutons qui se donnent l'apparence d'un lien (StopPanel, DisruptionRow,
   NetworkSummary × 2, et le chevron de repli). Volontairement sans `padding`, `color` ni
   `text-align` : ils diffèrent d'un site à l'autre — « tout afficher » garde le padding par
   défaut du bouton, l'horaire de StopPanel a le sien — et les aligner changerait le rendu.
   `font: inherit` est indispensable : un bouton n'hérite pas de la police du document. */
.linkButton {
  border: none;
  background: none;
  font: inherit;
  cursor: pointer;
  min-height: var(--tap);
}

/* Pastille ronde d'une ligne. La teinte arrive par `--line-color` (16 couleurs GTFS, ensemble non
   borné, donc impossible à figer en classes) ; la taille par `data-size`, dans ses deux seules
   valeurs en usage. `flex: 0 0 auto` est nécessaire quand la pastille sert de `leading` à une
   perturbation, et inerte dans les deux autres emplois (elle n'y a rien qui la comprime). */
.lineBadge {
  flex: 0 0 auto;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  background: var(--line-color);
  color: var(--surface);
  font-family: var(--font);
  font-weight: bold;
}

.lineBadge[data-size="s"] { width: 16px; height: 16px; font-size: 10px; }
.lineBadge[data-size="m"] { width: 18px; height: 18px; font-size: 11px; }
```

- [ ] **Step 3: Vérifier que rien n'a bougé**

Run: `cd frontend && npx vitest run && npx tsc -b && npm run lint && npm run build`
Expected: 69 tests verts, `tsc` et `lint` muets, build réussi. Aucun composant n'a été touché :
`shared.module.css` n'est encore importé par personne, les tokens ne sont encore utilisés par
personne.

- [ ] **Step 4: Commit**

```bash
git checkout -b qua-8-sortir-du-style-inline
git add frontend/src/index.css frontend/src/ui/shared.module.css
git commit -m "style(qua-8): tokens de rôle, garde [hidden] et motifs partagés"
```

---

### Task 2: Filet — `SheetFooter`, `StaleWarning`, `PanelHeader`

**Files:**
- Create: `frontend/src/ui/SheetFooter.test.tsx`
- Create: `frontend/src/ui/StaleWarning.test.tsx`
- Create: `frontend/src/ui/PanelHeader.test.tsx`

**Interfaces:**
- Consumes: `SheetFooter({ asOf: string | null })`, `StaleWarning({ stale: boolean })`,
  `PanelHeader({ title: string, onClose: () => void })`.
- Produces: rien (tests seuls).

- [ ] **Step 1: Écrire `SheetFooter.test.tsx`**

```tsx
// @vitest-environment jsdom
import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { SheetFooter } from "./SheetFooter";

// Sans `globals: true`, le nettoyage automatique de Testing Library ne s'enregistre pas.
afterEach(cleanup);

describe("SheetFooter", () => {
  it("annonce toujours la nature estimée des positions", () => {
    // Art. 5.4/5.7 de la Licence Mobilité : cette mention n'est pas cosmétique.
    render(<SheetFooter asOf={null} />);

    expect(screen.queryByText(/Position estimée \(pas de GPS en métro\)/)).not.toBeNull();
  });

  it("ne tamponne une heure que s'il y a un instantané", () => {
    // L'art. 5.7 interdit d'induire en erreur sur la date de mise à jour autant que sur le
    // contenu : avant le premier poll, il n'y a aucune heure à afficher.
    const avant = render(<SheetFooter asOf={null} />);
    expect(screen.queryByText(/Données IDFM du/)).toBeNull();
    avant.unmount();

    render(<SheetFooter asOf="2026-08-11T14:32:10Z" />);
    expect(screen.queryByText(/Données IDFM du/)).not.toBeNull();
  });
});
```

- [ ] **Step 2: Écrire `StaleWarning.test.tsx`**

```tsx
// @vitest-environment jsdom
import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { StaleWarning } from "./StaleWarning";

afterEach(cleanup);

describe("StaleWarning", () => {
  it("reste muette quand les positions se rafraîchissent", () => {
    render(<StaleWarning stale={false} />);

    expect(screen.queryByRole("status")).toBeNull();
  });

  it("annonce le gel par un role=status, pour qu'une panne ne soit jamais silencieuse", () => {
    render(<StaleWarning stale />);

    expect(screen.getByRole("status").textContent).toContain("Positions plus mises à jour");
  });
});
```

- [ ] **Step 3: Écrire `PanelHeader.test.tsx`**

```tsx
// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { PanelHeader } from "./PanelHeader";

afterEach(cleanup);

describe("PanelHeader", () => {
  it("porte le titre en en-tête de niveau 3", () => {
    render(<PanelHeader title="République" onClose={vi.fn()} />);

    expect(screen.getByRole("heading", { level: 3 }).textContent).toBe("République");
  });

  it("ferme par un bouton nommé, atteignable au lecteur d'écran", () => {
    // Le « ✕ » seul ne dit rien : c'est `aria-label` qui nomme le bouton.
    const onClose = vi.fn();
    render(<PanelHeader title="République" onClose={onClose} />);

    fireEvent.click(screen.getByRole("button", { name: "Fermer" }));

    expect(onClose).toHaveBeenCalledOnce();
  });
});
```

- [ ] **Step 4: Lancer les trois fichiers**

Run: `cd frontend && npx vitest run src/ui/SheetFooter.test.tsx src/ui/StaleWarning.test.tsx src/ui/PanelHeader.test.tsx`
Expected: PASS (7 tests). Ce sont des tests de régression sur du code correct : ils doivent passer
du premier coup.

- [ ] **Step 5: Mutation de contrôle — prouver que le filet mord**

Dans `SheetFooter.tsx`, remplacer `{asOf && ` Données IDFM du …`}` par
`{` Données IDFM du ${asOf}.`}` (l'heure s'affiche même sans instantané), puis relancer.

Run: `npx vitest run src/ui/SheetFooter.test.tsx`
Expected: FAIL sur « ne tamponne une heure que s'il y a un instantané ».
Puis annuler : `git checkout frontend/src/ui/SheetFooter.tsx` et relancer — PASS.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/ui/SheetFooter.test.tsx frontend/src/ui/StaleWarning.test.tsx frontend/src/ui/PanelHeader.test.tsx
git commit -m "test(qua-8): filet sur le pied, l'alerte de gel et l'en-tête de fiche"
```

---

### Task 3: Filet — `NetworkStatus` et `VehiclePanel`

**Files:**
- Create: `frontend/src/ui/NetworkStatus.test.tsx`
- Create: `frontend/src/ui/VehiclePanel.test.tsx`

**Interfaces:**
- Consumes: `NetworkStatus({ status: "loading" | "empty" | "error" | "ready" })`,
  `VehiclePanel({ vehicle: Vehicle, following?: boolean, onFollow?: () => void })`, le type
  `Vehicle` de `../api/types`, `statusLabel` de `./status`.
- Produces: rien (tests seuls).

- [ ] **Step 1: Écrire `NetworkStatus.test.tsx`**

```tsx
// @vitest-environment jsdom
import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { NetworkStatus } from "./NetworkStatus";

afterEach(cleanup);

describe("NetworkStatus", () => {
  it("s'effface dès que le plan est prêt", () => {
    render(<NetworkStatus status="ready" />);

    expect(screen.queryByRole("status")).toBeNull();
  });

  it("distingue le premier chargement d'une base encore vide", () => {
    // Sans ce bandeau, les deux donnaient le même écran blanc muet.
    const chargement = render(<NetworkStatus status="loading" />);
    // Pas de corps au chargement : le titre suffit.
    expect(screen.getByRole("status").textContent).toBe("Chargement du plan…");
    chargement.unmount();

    render(<NetworkStatus status="empty" />);
    expect(screen.queryByText("Plan en préparation")).not.toBeNull();
    expect(screen.getByRole("status").textContent).toContain("sans rien recharger");
  });

  it("dit l'échec sans jargon, et annonce la reprise automatique", () => {
    render(<NetworkStatus status="error" />);

    const banner = screen.getByRole("status");
    expect(banner.textContent).toContain("Données momentanément indisponibles");
    expect(banner.textContent).toContain("toutes les 10 secondes");
    // Le message s'adresse à quelqu'un qui veut voir passer son métro : ni « backend », ni
    // « GTFS », ni code HTTP. Le détail technique part en console (cf. useNetwork).
    expect(banner.textContent).not.toMatch(/backend|GTFS|HTTP|\b5\d{2}\b/);
  });
});
```

- [ ] **Step 2: Écrire `VehiclePanel.test.tsx`**

```tsx
// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { Vehicle } from "../api/types";
import { VehiclePanel } from "./VehiclePanel";

afterEach(cleanup);

// +5 s de marge : sans elle, les millisecondes écoulées entre la fixture et le rendu font tomber
// le calcul de formatEta sur la minute inférieure et le test devient intermittent.
const vehicle = (overrides: Partial<Vehicle> = {}): Vehicle => ({
  journeyRef: "J-1", lineId: "9", lat: 48.87, lng: 2.33, bearing: 0,
  status: "ON_TIME", headsign: "Pont de Sèvres", nextStop: "Havre-Caumartin",
  expectedTime: new Date(Date.now() + 95_000).toISOString(),
  recordedAt: null, confidence: "RELIABLE",
  ...overrides,
});

describe("VehiclePanel", () => {
  it("montre le prochain arrêt, l'arrivée à la seconde et l'état", () => {
    // La fiche d'un train n'en montre qu'un : c'est le seul endroit où la forme en phrase
    // (« dans 1 min 35 s ») a sa place, contrairement aux listes de StopPanel.
    render(<VehiclePanel vehicle={vehicle()} />);

    expect(screen.queryByText("Havre-Caumartin")).not.toBeNull();
    expect(screen.queryByText(/dans 1 min/)).not.toBeNull();
    expect(screen.queryByText(/à l'heure/)).not.toBeNull();
    // Le métro n'a pas de GPS : la position est toujours estimée, jamais mesurée.
    expect(screen.queryByText("Position : estimée (horaire)")).not.toBeNull();
  });

  it("n'avertit d'un placement approximatif que pour une course à un seul appel", () => {
    const fiable = render(<VehiclePanel vehicle={vehicle()} />);
    expect(screen.queryByText(/Position approximative/)).toBeNull();
    fiable.unmount();

    render(<VehiclePanel vehicle={vehicle({ confidence: "APPROXIMATE" })} />);
    expect(screen.queryByText(/n'annonce qu'un seul arrêt pour ce train/)).not.toBeNull();
  });

  it("ne cite la fraîcheur de la course que si le flux l'a donnée", () => {
    const sans = render(<VehiclePanel vehicle={vehicle({ recordedAt: null })} />);
    expect(screen.queryByText(/Donnée du/)).toBeNull();
    sans.unmount();

    render(<VehiclePanel vehicle={vehicle({ recordedAt: "2026-08-11T14:32:10Z" })} />);
    expect(screen.queryByText(/Donnée du/)).not.toBeNull();
  });

  it("bascule le suivi et le dit dans son libellé", () => {
    const onFollow = vi.fn();
    const inactif = render(<VehiclePanel vehicle={vehicle()} onFollow={onFollow} />);
    expect(screen.getByRole("button").textContent).toBe("◉ Suivre");

    fireEvent.click(screen.getByRole("button"));
    expect(onFollow).toHaveBeenCalledOnce();
    inactif.unmount();

    render(<VehiclePanel vehicle={vehicle()} following onFollow={onFollow} />);
    expect(screen.getByRole("button").textContent).toBe("◉ Suivi actif");
  });
});
```

- [ ] **Step 3: Lancer les deux fichiers**

Run: `cd frontend && npx vitest run src/ui/NetworkStatus.test.tsx src/ui/VehiclePanel.test.tsx`
Expected: PASS (7 tests).

- [ ] **Step 4: Mutation de contrôle**

Dans `VehiclePanel.tsx`, retirer la garde `vehicle.confidence === "APPROXIMATE" &&` (l'encart
s'affiche pour tous les trains), puis relancer.

Run: `npx vitest run src/ui/VehiclePanel.test.tsx`
Expected: FAIL sur « n'avertit d'un placement approximatif que pour une course à un seul appel ».
Puis `git checkout frontend/src/ui/VehiclePanel.tsx` et relancer — PASS.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/ui/NetworkStatus.test.tsx frontend/src/ui/VehiclePanel.test.tsx
git commit -m "test(qua-8): filet sur le bandeau d'état et la fiche d'un train"
```

---

### Task 4: Filet — `LinePicker`, le composant le plus chargé sans aucun test

**Files:**
- Create: `frontend/src/ui/LinePicker.test.tsx`

**Interfaces:**
- Consumes: `LinePicker({ lines, disrupted, counts, disruptions, disruptionsOpen, visible,
  onToggle })` — `lines: NetworkLine[]`, `disrupted: NetworkLine[]`, `counts: Map<string, number>`,
  `disruptions: Map<string, LineDisruptions>`, `disruptionsOpen: boolean`,
  `visible: Set<string> | null`, `onToggle: (lineId: string) => void`. Types dans `../api/types`.
- Produces: rien (tests seuls).

- [ ] **Step 1: Écrire `LinePicker.test.tsx`**

```tsx
// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { LineDisruptions, NetworkLine } from "../api/types";
import { LinePicker } from "./LinePicker";

afterEach(cleanup);

const LIGNE_9: NetworkLine = { id: "C01379", shortName: "9", color: "#D5C900", mode: "METRO" };
const LIGNE_8: NetworkLine = { id: "C01378", shortName: "8", color: "#CEADD2", mode: "METRO" };

const perturbation = (severity: LineDisruptions["severity"], titre: string): LineDisruptions => ({
  lineId: LIGNE_8.id,
  severity,
  items: [{ severity, cause: "PERTURBATION", title: titre, shortMessage: "Trafic", detail: "" }],
});

function renderPicker(props: Partial<Parameters<typeof LinePicker>[0]> = {}) {
  const onToggle = vi.fn();
  render(
    <LinePicker
      lines={[LIGNE_9, LIGNE_8]}
      disrupted={[]}
      counts={new Map([[LIGNE_9.id, 12], [LIGNE_8.id, 7]])}
      disruptions={new Map()}
      disruptionsOpen={false}
      visible={null}
      onToggle={onToggle}
      {...props}
    />,
  );
  return { onToggle };
}

describe("LinePicker", () => {
  it("compte les trains de chaque ligne, pastille et compteur", () => {
    renderPicker();

    // Deux boutons, un par ligne suivie ; le shortName vit dans la pastille.
    // `getByTitle` et non `getByRole(…, { name })` : ce bouton n'a pas d'`aria-label`, donc son nom
    // accessible est son contenu textuel (« 912 »), pas son infobulle.
    expect(screen.getAllByRole("button")).toHaveLength(2);
    expect(screen.getByTitle(/ligne 9/).textContent).toBe("912");
    expect(screen.getByTitle(/ligne 8/).textContent).toBe("87");
  });

  it("bascule la ligne cliquée, et elle seule", () => {
    const { onToggle } = renderPicker();

    fireEvent.click(screen.getByTitle(/ligne 8/));

    expect(onToggle).toHaveBeenCalledExactlyOnceWith(LIGNE_8.id);
  });

  it("remplace le compte par la perturbation dans l'infobulle, et ajoute son glyphe", () => {
    // Une information portée par la seule couleur est illisible : le glyphe la double.
    renderPicker({
      disruptions: new Map([[LIGNE_8.id, perturbation("BLOQUANTE", "Métro 8 : Trafic interrompu")]]),
    });

    const pastille = screen.getByTitle(/trafic bloqué/);
    expect(pastille.getAttribute("title")).toContain("Métro 8 : Trafic interrompu");
    expect(pastille.textContent).toContain("✕");
    // La ligne 9 n'a rien : son infobulle reste le compteur de trains.
    expect(screen.getByTitle(/12 train\(s\)/)).not.toBeNull();
  });

  it("ne déplie la liste des perturbations que sur demande", () => {
    const disruptions = new Map([
      [LIGNE_8.id, perturbation("PERTURBEE", "Métro 8 : Incident - Trafic ralenti")],
    ]);
    const replie = renderPicker({ disruptions, disrupted: [LIGNE_8], disruptionsOpen: false });
    expect(screen.queryByText(/Incident - Trafic ralenti/)).toBeNull();
    replie.unmount();

    renderPicker({ disruptions, disrupted: [LIGNE_8], disruptionsOpen: true });
    // Titre raccourci par disruptionTitle : la pastille de ligne porte déjà « Métro 8 ».
    expect(screen.queryByText("Incident - Trafic ralenti")).not.toBeNull();
  });
});
```

- [ ] **Step 2: Lancer**

Run: `cd frontend && npx vitest run src/ui/LinePicker.test.tsx`
Expected: PASS (4 tests).

- [ ] **Step 3: Mutation de contrôle**

Dans `LinePicker.tsx`, remplacer `onClick={() => onToggle(line.id)}` par
`onClick={() => onToggle(lines[0].id)}`, puis relancer.

Run: `npx vitest run src/ui/LinePicker.test.tsx`
Expected: FAIL sur « bascule la ligne cliquée, et elle seule ».
Puis `git checkout frontend/src/ui/LinePicker.tsx` et relancer — PASS.

- [ ] **Step 4: Commit**

```bash
git add frontend/src/ui/LinePicker.test.tsx
git commit -m "test(qua-8): filet sur le sélecteur de lignes"
```

---

### Task 5: Filet — angles non couverts de `DisruptionRow` et `StopPanel`

**Files:**
- Modify: `frontend/src/ui/DisruptionRow.test.tsx:37` (ajouter deux tests)
- Modify: `frontend/src/ui/StopPanel.test.tsx` (ajouter un test)

**Interfaces:**
- Consumes: `DisruptionRow({ item: DisruptionItem, leading?: ReactNode })`,
  `StopPanel({ data, onSelectTrain?, onSelectLine? })`, `badgeText` et `disruptionTitle` de
  `./disruptionText`.
- Produces: rien (tests seuls).

- [ ] **Step 1: Ajouter deux tests à `DisruptionRow.test.tsx`**

Insérer avant la fermeture du `describe("DisruptionRow", …)` :

```tsx
  it("substitue le libellé de gravité au résumé quand le flux met « Autre »", () => {
    // `badgeText` a ses tests unitaires ; rien ne prouvait que le composant l'appelle. Mesuré :
    // « Métro 14 : Information - Autre » n'a de sens que par son libellé de gravité.
    render(<ul><DisruptionRow item={item({ shortMessage: "Autre", severity: "INFORMATION" })} /></ul>);

    expect(screen.queryByText("information")).not.toBeNull();
    expect(screen.queryByText("Autre")).toBeNull();
  });

  it("raccourcit le titre quand une pastille de ligne le précède, pas sinon", () => {
    // La présence de `leading` EST la condition de `disruptionTitle` : le sélecteur montre la
    // ligne dans sa pastille, la fiche station n'a que le nom de la station.
    const avecPastille = render(
      <ul><DisruptionRow item={item()} leading={<span>9</span>} /></ul>,
    );
    expect(screen.queryByText("Incident - Train stationne")).not.toBeNull();
    expect(screen.queryByText("Métro 5 : Incident - Train stationne")).toBeNull();
    expect(screen.queryByText("9")).not.toBeNull();
    avecPastille.unmount();

    render(<ul><DisruptionRow item={item()} /></ul>);
    expect(screen.queryByText("Métro 5 : Incident - Train stationne")).not.toBeNull();
  });
```

- [ ] **Step 2: Ajouter un test à `StopPanel.test.tsx`**

Insérer avant la fermeture du `describe("StopPanel", …)` :

```tsx
  it("isole la ligne dont on clique la pastille", () => {
    // Isolement inconditionnel, comme un clic dans le sélecteur du bas : quel que soit le filtre
    // courant, ce clic ne laisse que cette ligne (décision produit).
    const onSelectLine = vi.fn();
    render(<StopPanel data={departures()} onSelectLine={onSelectLine} />);

    fireEvent.click(screen.getByRole("button", { name: "N'afficher que la ligne 3" }));

    expect(onSelectLine).toHaveBeenCalledExactlyOnceWith("3");
  });
```

- [ ] **Step 3: Lancer les deux fichiers**

Run: `cd frontend && npx vitest run src/ui/DisruptionRow.test.tsx src/ui/StopPanel.test.tsx`
Expected: PASS.

- [ ] **Step 4: Mutation de contrôle**

Dans `DisruptionRow.tsx`, remplacer `disruptionTitle(item.title, leading != null)` par
`item.title`, puis relancer.

Run: `npx vitest run src/ui/DisruptionRow.test.tsx`
Expected: FAIL sur « raccourcit le titre quand une pastille de ligne le précède ».
Puis `git checkout frontend/src/ui/DisruptionRow.tsx` et relancer — PASS.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/ui/DisruptionRow.test.tsx frontend/src/ui/StopPanel.test.tsx
git commit -m "test(qua-8): angles non couverts du badge, du titre et de l'isolement de ligne"
```

---

### Task 6: Filet — trois angles de `Sheet`, et la mesure du `PointerEvent` de jsdom 27

**Files:**
- Modify: `frontend/src/ui/Sheet.test.tsx:48-70` (`renderSheet` accepte `asOf`) et fin de fichier
- Modify: `CLAUDE.md` (seulement si la mesure de l'étape 5 le justifie)

**Interfaces:**
- Consumes: `Sheet({ cran, onCranChange, viewportHeight, header, summary, children, footer, alert,
  label, onPeekHeight, asOf })`, les helpers locaux `firePointer(element, type, clientY, timeStamp)`
  et `renderSheet(cran)`, `MOVE_THRESHOLD = 6` (constante privée de `Sheet.tsx`).
- Produces: `renderSheet(cran, asOf?)` — signature élargie, utilisée par les tests suivants.

- [ ] **Step 1: Élargir `renderSheet` pour accepter un instantané**

Remplacer la signature et l'appel du composant dans `renderSheet` :

```tsx
function renderSheet(cran: Cran, asOf: string | null = null) {
  const onCranChange = vi.fn();
  const onPeekHeight = vi.fn();
  render(
    <Sheet
      cran={cran}
      onCranChange={onCranChange}
      viewportHeight={VIEWPORT}
      header={<p>titre fiche</p>}
      summary={<p>résumé</p>}
      footer={<p>pied</p>}
      alert={<p>alerte gel</p>}
      label="État du réseau"
      onPeekHeight={onPeekHeight}
      asOf={asOf}
    >
      <p>corps</p>
    </Sheet>,
  );
  const handle = screen.getByRole("button", { name: "Changer la hauteur du panneau" });
  return { onCranChange, onPeekHeight, handle, body: screen.getByText("corps").parentElement! };
}
```

- [ ] **Step 2: Ajouter les trois tests**

À la fin du fichier :

```tsx
describe("Sheet — fraîcheur sur la poignée", () => {
  it("affiche l'heure de l'instantané, décorative pour le lecteur d'écran", () => {
    // Sous 720 px, la feuille se replie jusqu'à sa poignée : c'est le seul endroit où la date de
    // la donnée reste lisible (art. 5.7). Le texte de l'attribution MapLibre ne peut pas la
    // porter — il se fige à la construction du contrôle.
    const { handle } = renderSheet("apercu", "2026-08-11T14:32:10Z");

    const heure = handle.querySelector('[aria-hidden="true"]');
    expect(heure).not.toBeNull();
    expect(heure!.textContent).toMatch(/^estimé \d{2}:\d{2}$/);
  });

  it("n'affiche rien avant le premier poll", () => {
    const { handle } = renderSheet("apercu", null);

    expect(handle.textContent).toBe("");
  });
});

describe("Sheet — fin de geste", () => {
  it("termine le glissement sur un pointercancel, comme sur un pointerup", () => {
    // Le système peut confisquer le pointeur (appel entrant, geste système) : sans ce
    // gestionnaire, la feuille resterait collée au doigt disparu.
    const { onCranChange, handle } = renderSheet("moitie");

    firePointer(handle, "pointerdown", 400);
    firePointer(handle, "pointermove", 100);
    fireEvent(handle, new MouseEvent("pointercancel", { bubbles: true, cancelable: true }));

    // 422 + 300 = 722, plus proche de 760 (plein) que de 422 (moitié) — même issue qu'un lâcher.
    expect(onCranChange).toHaveBeenCalledWith("plein");
  });

  it("garde un toucher de moins de 7 px pour un clic, et glisse au-delà", () => {
    // MOVE_THRESHOLD = 6 : un toucher sans déplacement réel doit rester un clic, sinon la
    // poignée devient inerte au moindre tremblement de doigt.
    const court = renderSheet("apercu");
    firePointer(court.handle, "pointerdown", 400);
    firePointer(court.handle, "pointermove", 394); // 6 px : encore ambigu
    firePointer(court.handle, "pointerup", 394);
    court.onCranChange.mockClear();
    fireEvent.click(court.handle, { detail: 1 });
    // `moved` est resté faux : le clic natif avance d'un cran.
    expect(court.onCranChange).toHaveBeenCalledWith("moitie");
    cleanup();

    const long = renderSheet("apercu");
    firePointer(long.handle, "pointerdown", 400);
    firePointer(long.handle, "pointermove", 393); // 7 px : c'est un glissement
    firePointer(long.handle, "pointerup", 393);
    long.onCranChange.mockClear();
    fireEvent.click(long.handle, { detail: 1 });
    // `moved` est vrai : le clic natif qui suit le geste ne doit pas avancer d'un cran de plus.
    expect(long.onCranChange).not.toHaveBeenCalled();
  });
});
```

- [ ] **Step 3: Lancer**

Run: `cd frontend && npx vitest run src/ui/Sheet.test.tsx`
Expected: PASS (17 tests : les 12 existants + 5 ajoutés).

- [ ] **Step 4: Mutation de contrôle**

Dans `Sheet.tsx`, remplacer `onPointerCancel={onHandlePointerUp}` par
`onPointerCancel={undefined}`, puis relancer.

Run: `npx vitest run src/ui/Sheet.test.tsx`
Expected: FAIL sur « termine le glissement sur un pointercancel ».
Puis `git checkout frontend/src/ui/Sheet.tsx` et relancer — PASS.

- [ ] **Step 5: Mesurer si `firePointer` est encore nécessaire**

jsdom 27 fournit un `PointerEvent` global (jsdom 26 non). Vérifier ce que `fireEvent.pointerDown`
transmet réellement, en ajoutant temporairement à `Sheet.test.tsx` :

```tsx
it("SONDE — fireEvent.pointerDown transmet-il clientY ?", () => {
  const { onCranChange, handle } = renderSheet("moitie");
  fireEvent.pointerDown(handle, { clientY: 400 });
  fireEvent.pointerMove(handle, { clientY: 100 });
  fireEvent.pointerUp(handle, { clientY: 100 });
  console.log("SONDE cran =", onCranChange.mock.calls);
});
```

Run: `npx vitest run src/ui/Sheet.test.tsx --reporter=verbose 2>&1 | grep SONDE`

Deux issues possibles, aucune ne demande de décision : **si `cran` vaut `["plein"]`**, jsdom 27
transmet bien les coordonnées, et `firePointer` ne subsiste plus que pour maîtriser `timeStamp`
(non settable par l'`init` de `fireEvent`, et `0` est ignoré par React qui calcule
`event.timeStamp || Date.now()`) — réduire alors son commentaire de tête à cette seule raison, et
la ligne correspondante de CLAUDE.md. **Si `cran` est vide ou contient `NaN`**, la raison d'origine
tient encore : ne rien changer. Dans les deux cas, **supprimer la sonde** avant de committer.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/ui/Sheet.test.tsx CLAUDE.md
git commit -m "test(qua-8): fraîcheur sur la poignée, pointercancel et seuil de mouvement"
```

---

### Task 7: Conversion — `SheetFooter`, `StaleWarning`, `PanelHeader`

**Files:**
- Create: `frontend/src/ui/SheetFooter.module.css`, `frontend/src/ui/StaleWarning.module.css`,
  `frontend/src/ui/PanelHeader.module.css`
- Modify: `frontend/src/ui/SheetFooter.tsx:14`, `frontend/src/ui/StaleWarning.tsx:16`,
  `frontend/src/ui/PanelHeader.tsx:9-26`

**Interfaces:**
- Consumes: les tokens de la tâche 1.
- Produces: rien de nouveau — les trois composants gardent leurs props.

- [ ] **Step 1: Écrire les trois modules**

`SheetFooter.module.css` :

```css
.footer {
  margin-top: 6px;
  color: var(--text-muted);
}
```

`StaleWarning.module.css` :

```css
.warning {
  margin-top: 6px;
  color: var(--warn);
}
```

`PanelHeader.module.css` :

```css
.header {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  margin: 0 0 8px;
}

/* Aucun style de police sur le titre : le `<h3>` n'en avait pas non plus et héritait du défaut du
   navigateur (gras, 1.17em). En fixer un le rétrécirait. */
.title {
  margin: 0;
  flex: 1;
  min-width: 0;
}

/* Pas de `font-family` ici : le bouton ne portait déjà que `font-size`, donc il gardait la police
   du widget. En poser une changerait le rendu du « ✕ ». */
.close {
  flex: 0 0 auto;
  border: none;
  background: none;
  cursor: pointer;
  font-size: 20px;
  line-height: 1;
  padding: 4px;
  min-width: var(--tap);
  min-height: var(--tap);
}
```

- [ ] **Step 2: Basculer les trois composants**

`SheetFooter.tsx` : ajouter `import styles from "./SheetFooter.module.css";` et remplacer
`<div style={{ color: "#666", marginTop: 6 }}>` par `<div className={styles.footer}>`.

`StaleWarning.tsx` : ajouter `import styles from "./StaleWarning.module.css";` et remplacer
`<div style={{ color: "#b45309", marginTop: 6 }} role="status">` par
`<div className={styles.warning} role="status">`.

`PanelHeader.tsx` : ajouter `import styles from "./PanelHeader.module.css";` puis
`<div className={styles.header}>`, `<h3 className={styles.title}>`,
`<button className={styles.close} onClick={onClose} aria-label="Fermer">` — les trois attributs
`style` disparaissent.

- [ ] **Step 3: Vérifier**

Run: `cd frontend && npx vitest run && npx tsc -b && npm run lint && npm run build`
Expected: tous les tests verts (le filet de la tâche 2 couvre ces trois composants), `tsc` et
`lint` muets, build réussi.

- [ ] **Step 4: Commit**

```bash
git add frontend/src/ui/SheetFooter.* frontend/src/ui/StaleWarning.* frontend/src/ui/PanelHeader.*
git commit -m "style(qua-8): pied, alerte de gel et en-tête de fiche en CSS Modules"
```

---

### Task 8: Conversion — `NetworkStatus`, premier état en `data-*`

**Files:**
- Create: `frontend/src/ui/NetworkStatus.module.css`
- Modify: `frontend/src/ui/NetworkStatus.tsx:32-56`

**Interfaces:**
- Consumes: tokens de la tâche 1.
- Produces: le motif `data-status` sur le bandeau — première application de la convention d'état.

- [ ] **Step 1: Écrire `NetworkStatus.module.css`**

```css
.banner {
  position: absolute;
  top: 12px;
  /* `max-width` seul débordait sous 384 px : les bornes gauche/droite le rendent fluide,
     `margin: auto` le garde centré au-dessus du seuil. */
  left: 12px;
  right: 12px;
  max-width: 360px;
  margin: 0 auto;
  padding: 10px 14px;
  background: var(--surface);
  border-radius: 8px;
  border-left: 4px solid var(--accent);
  box-shadow: var(--shadow-card);
  font-size: 13px;
  /* La carte reste manipulable sous le bandeau (pas de bouton dedans). */
  pointer-events: none;
}

/* Seul l'échec change de liseré : chargement et base vide sont des états d'attente. */
.banner[data-status="error"] {
  border-left-color: var(--warn);
}

.body {
  color: var(--text-detail);
  margin-top: 4px;
}
```

- [ ] **Step 2: Basculer le composant**

```tsx
import styles from "./NetworkStatus.module.css";

// … corps inchangé jusqu'au return …

  return (
    <div className={styles.banner} data-status={status} role="status">
      <b>{message.title}</b>
      {message.body && <div className={styles.body}>{message.body}</div>}
    </div>
  );
```

- [ ] **Step 3: Vérifier**

Run: `cd frontend && npx vitest run src/ui/NetworkStatus.test.tsx && npx tsc -b && npm run lint && npm run build`
Expected: 3 tests verts, `tsc` et `lint` muets, build réussi.

- [ ] **Step 4: Commit**

```bash
git add frontend/src/ui/NetworkStatus.tsx frontend/src/ui/NetworkStatus.module.css
git commit -m "style(qua-8): bandeau d'état en CSS Modules, liseré par data-status"
```

---

### Task 9: Conversion — `FloatingCard` et `App`, la prop `style` devient `className`

**Files:**
- Create: `frontend/src/ui/FloatingCard.module.css`, `frontend/src/App.module.css`
- Modify: `frontend/src/ui/FloatingCard.tsx` (entier), `frontend/src/App.tsx:320,352,372,379`

**Interfaces:**
- Consumes: tokens de la tâche 1.
- Produces: `FloatingCard({ anchor: "top-right" | "bottom-left", className?: string, children })` —
  la prop `style?: CSSProperties` **disparaît**, ainsi que la constante `ANCHORS`. `App` fournit
  désormais les variantes par classe (`.ficheStation` / `.ficheTrain`), et la variable `ficheWidth`
  d'`App.tsx` disparaît.

- [ ] **Step 1: Écrire `FloatingCard.module.css`**

```css
.card {
  position: absolute;
  padding: 16px;
  background: var(--surface);
  border-radius: 8px;
  box-shadow: var(--shadow-card);
  font-size: 14px;
}

/* L'ancrage remplace la table `ANCHORS` : deux positions, connues à l'avance. */
.card[data-anchor="top-right"] { top: 12px; right: 12px; }
.card[data-anchor="bottom-left"] { bottom: 12px; left: 12px; }
```

- [ ] **Step 2: Écrire `App.module.css`**

```css
.map {
  position: absolute;
  inset: 0;
}

/* Fiche du rendu large. La largeur dépend de ce qu'elle montre : une station cite ses lignes et
   ses passages, d'où 20 px de plus qu'un train. Deux classes plutôt qu'un `data-kind` :
   `FloatingCard` ne relaie pas d'attributs arbitraires, et lui en ajouter un pour deux largeurs
   serait un contrat de trop. */
.ficheStation {
  max-height: 70dvh;
  overflow-y: auto;
  width: 280px;
}

.ficheTrain {
  max-height: 70dvh;
  overflow-y: auto;
  width: 260px;
}

/* Panneau du réseau : plus dense que la fiche (padding et police réduits). */
.reseau {
  padding: 10px 12px;
  font-size: 13px;
  max-width: 300px;
}
```

- [ ] **Step 3: Réécrire `FloatingCard.tsx`**

```tsx
import type { ReactNode } from "react";
import styles from "./FloatingCard.module.css";

type Anchor = "top-right" | "bottom-left";

interface Props {
  anchor: Anchor;
  /** Ce que ce panneau-là fait différemment (padding, police, largeur), fourni par le parent. */
  className?: string;
  children: ReactNode;
}

/**
 * Carte flottante du rendu large. Existe pour que les panneaux ignorent où ils sont posés : sur
 * écran étroit c'est `Sheet` qui les accueille, sans qu'ils aient à le savoir.
 */
export function FloatingCard({ anchor, className, children }: Props) {
  return (
    <div className={className ? `${styles.card} ${className}` : styles.card} data-anchor={anchor}>
      {children}
    </div>
  );
}
```

- [ ] **Step 4: Basculer `App.tsx`**

Ajouter `import styles from "./App.module.css";`, puis :

- ligne 320 : **supprimer** `const ficheWidth = station ? 280 : 260;` — la largeur est désormais
  portée par la classe, et `station` est déjà dans la portée du rendu.
- ligne 352 : `<div ref={container} className={styles.map} />`
- ligne 372 :
  `<FloatingCard anchor="top-right" className={station ? styles.ficheStation : styles.ficheTrain}>`
- ligne 379 : `<FloatingCard anchor="bottom-left" className={styles.reseau}>`

- [ ] **Step 5: Vérifier**

Run: `cd frontend && npx vitest run && npx tsc -b && npm run lint && npm run build`
Expected: 76 tests verts, `tsc` muet — en particulier plus aucune référence à `CSSProperties` dans
`FloatingCard.tsx` ni à `ficheWidth` dans `App.tsx`.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/ui/FloatingCard.tsx frontend/src/ui/FloatingCard.module.css frontend/src/App.tsx frontend/src/App.module.css
git commit -m "style(qua-8): carte flottante et App en CSS Modules, prop style remplacée par className"
```

---

### Task 10: Conversion — `VehiclePanel`, l'état de suivi passe en `aria-pressed`

**Files:**
- Create: `frontend/src/ui/VehiclePanel.module.css`
- Modify: `frontend/src/ui/VehiclePanel.tsx:18-53`
- Modify: `frontend/src/ui/VehiclePanel.test.tsx` (ajouter l'assertion `aria-pressed`)

**Interfaces:**
- Consumes: tokens de la tâche 1.
- Produces: le bouton de suivi expose `aria-pressed={following}` — état d'accessibilité qui
  n'existait pas, et sur lequel le style s'appuie désormais.

- [ ] **Step 1: Écrire `VehiclePanel.module.css`**

```css
.line {
  margin: 4px 0;
}

.muted {
  margin: 4px 0;
  color: var(--text-muted);
}

.approx {
  margin: 8px 0 0;
  padding: 6px 8px;
  background: var(--amber-bg-soft);
  border-radius: 6px;
  color: var(--amber-text);
}

/* `font-family` explicite : un bouton n'hérite pas de la police du document, et l'attribut
   remplacé posait `font: 13px sans-serif`. */
.follow {
  margin-top: 8px;
  padding: 6px 12px;
  border: 1px solid var(--accent);
  border-radius: 6px;
  cursor: pointer;
  background: var(--surface);
  color: var(--accent);
  font-family: var(--font);
  font-size: 13px;
  min-height: var(--tap);
}

/* Suivi actif : négatif. L'état vit dans `aria-pressed`, que le bouton n'exposait pas avant ce
   chantier — le style s'y appuie au lieu de le dupliquer en classe. */
.follow[aria-pressed="true"] {
  background: var(--accent);
  color: var(--surface);
}
```

- [ ] **Step 2: Basculer le composant**

```tsx
import styles from "./VehiclePanel.module.css";

// …

  return (
    <>
      <p className={styles.line}>
        Prochain arrêt : <b>{vehicle.nextStop}</b>
      </p>
      <p className={styles.line}>
        Arrivée estimée : <b>{formatEta(vehicle.expectedTime, { withSeconds: true })}</b>
      </p>
      <p className={styles.line}>État : {statusLabel(vehicle.status)}</p>
      {/* Le métro n'a pas de GPS : la position est TOUJOURS estimée par interpolation, jamais
          mesurée. Le backend ne peut produire aucun autre cas. */}
      <p className={styles.muted}>Position : estimée (horaire)</p>
      {vehicle.confidence === "APPROXIMATE" && (
        <p className={styles.approx}>
          Position approximative : le flux temps réel n'annonce qu'un seul arrêt pour ce train.
        </p>
      )}
      {vehicle.recordedAt && (
        <p className={styles.muted}>
          Donnée du {new Date(vehicle.recordedAt).toLocaleTimeString("fr-FR")}
        </p>
      )}
      <button onClick={onFollow} className={styles.follow} aria-pressed={following}>
        {following ? "◉ Suivi actif" : "◉ Suivre"}
      </button>
    </>
  );
```

- [ ] **Step 3: Ajouter l'assertion d'état au test**

Dans le test « bascule le suivi et le dit dans son libellé » de `VehiclePanel.test.tsx`, compléter
les deux branches :

```tsx
    expect(screen.getByRole("button").getAttribute("aria-pressed")).toBe("false");
```

après l'assertion `"◉ Suivre"`, et :

```tsx
    expect(screen.getByRole("button").getAttribute("aria-pressed")).toBe("true");
```

après l'assertion `"◉ Suivi actif"`.

- [ ] **Step 4: Vérifier**

Run: `cd frontend && npx vitest run src/ui/VehiclePanel.test.tsx && npx tsc -b && npm run lint && npm run build`
Expected: 4 tests verts.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/ui/VehiclePanel.tsx frontend/src/ui/VehiclePanel.module.css frontend/src/ui/VehiclePanel.test.tsx
git commit -m "style(qua-8): fiche d'un train en CSS Modules, suivi porté par aria-pressed"
```

---

### Task 11: Conversion — `NetworkSummary`, premier usage de `composes`

**Files:**
- Create: `frontend/src/ui/NetworkSummary.module.css`
- Modify: `frontend/src/ui/NetworkSummary.tsx:27-74`

**Interfaces:**
- Consumes: `linkButton` de `./shared.module.css` (tâche 1).
- Produces: rien de nouveau — mêmes props.

- [ ] **Step 1: Écrire `NetworkSummary.module.css`**

```css
.head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
}

.actions {
  display: flex;
  align-items: center;
  gap: 4px;
}

/* Sans `padding: 0` : ce bouton gardait le padding par défaut de l'UA, contrairement aux deux
   suivants. L'aligner changerait sa boîte. */
.showAll {
  composes: linkButton from "./shared.module.css";
  color: var(--accent);
}

.chevron {
  composes: linkButton from "./shared.module.css";
  color: var(--text-muted);
  padding: 0;
}

.resume {
  color: var(--text-muted);
  margin-top: 2px;
}

.disrupted {
  composes: linkButton from "./shared.module.css";
  color: var(--warn);
  padding: 0;
  margin-top: 6px;
  text-align: left;
}
```

- [ ] **Step 2: Basculer le composant**

```tsx
import styles from "./NetworkSummary.module.css";

// …

  return (
    <>
      <div className={styles.head}>
        <b>{inService ? `${total} trains en circulation` : "Service terminé"}</b>
        <div className={styles.actions}>
          {canShowAll && (
            <button onClick={onShowAll} className={styles.showAll}>
              tout afficher
            </button>
          )}
          {collapsible && (
            <button
              onClick={onToggleExpanded}
              aria-expanded={expanded}
              aria-label={expanded ? "Replier le sélecteur de lignes" : "Déplier le sélecteur de lignes"}
              className={styles.chevron}
            >
              {expanded ? "▾" : "▸"}
            </button>
          )}
        </div>
      </div>
      {/* Sans cette phrase, une carte vide se lit comme une panne — c'est le seul moment où le
          silence de l'appli et son échec se ressemblent. Aucune heure citée : le premier métro
          dépend de la ligne, et la fenêtre de service vit côté serveur. */}
      {!inService && (
        <div className={styles.resume}>Reprise au premier métro.</div>
      )}
      {disruptedCount > 0 && (
        <button
          onClick={onToggleDisruptions}
          className={styles.disrupted}
          aria-expanded={disruptionsOpen}
        >
          {disruptedCount === 1 ? "1 ligne perturbée" : `${disruptedCount} lignes perturbées`}
          {disruptionsOpen ? " ▾" : " ▸"}
        </button>
      )}
    </>
  );
```

- [ ] **Step 3: Vérifier que `composes` a bien produit deux classes**

Run: `cd frontend && npx vitest run src/ui/NetworkSummary.test.tsx && npm run build && grep -c "linkButton" dist/assets/*.css`
Expected: 6 tests verts, build réussi, et le `grep` trouve la classe scopée du motif partagé dans le
CSS émis (≥ 1). Si le compte est 0, `composes` n'a pas été résolu : basculer sur la multi-classe
(`className={`${shared.linkButton} ${styles.showAll}`}` avec
`import shared from "./shared.module.css"`), qui rend le même résultat sans dépendre de la
fonctionnalité.

- [ ] **Step 4: Commit**

```bash
git add frontend/src/ui/NetworkSummary.tsx frontend/src/ui/NetworkSummary.module.css
git commit -m "style(qua-8): résumé du réseau en CSS Modules, bouton-lien partagé par composes"
```

---

### Task 12: Conversion — `DisruptionRow`, la gravité passe en `data-severity`

**Files:**
- Create: `frontend/src/ui/DisruptionRow.module.css`
- Modify: `frontend/src/ui/DisruptionRow.tsx:13-64`

**Interfaces:**
- Consumes: `linkButton` de `./shared.module.css`, la variable `--sev` posée par `[data-severity]`
  dans `index.css` (tâche 1).
- Produces: la convention `data-severity` sur l'élément porteur. `severityStyle` est encore appelée
  pour `label` **et** `color` ici — `color` n'est retirée qu'en tâche 13, quand son dernier
  consommateur aura migré.

- [ ] **Step 1: Écrire `DisruptionRow.module.css`**

```css
.row {
  display: flex;
  gap: 6px;
  align-items: flex-start;
  padding: 6px 0;
  border-top: 1px solid var(--border-subtle);
}

.text {
  min-width: 0;
}

/* La teinte descend de `[data-severity]`, posé sur la rangée (cf. index.css) : quatre valeurs,
   une seule source, et le défaut couvre une gravité que le flux aurait inventée. */
.badge {
  display: inline-block;
  padding: 1px 6px;
  border-radius: 4px;
  background: var(--sev);
  color: var(--surface);
  font-weight: bold;
  font-size: 11px;
}

.toggle {
  composes: linkButton from "./shared.module.css";
  padding: 0;
  margin-left: 6px;
  color: var(--accent);
  text-align: left;
}

.title {
  color: var(--text-detail);
  margin-left: 6px;
}

/* `pre-line` : le texte brut du serveur garde ses sauts de ligne. Hauteur bornée, certains
   messages font un paragraphe entier. */
.detail {
  color: var(--text-detail-open);
  margin-top: 4px;
  white-space: pre-line;
  max-height: 140px;
  overflow-y: auto;
}
```

- [ ] **Step 2: Basculer le composant**

```tsx
import { useState, type ReactNode } from "react";
import type { DisruptionItem } from "../api/types";
import { severityStyle } from "./severity";
import { badgeText, disruptionTitle } from "./disruptionText";
import styles from "./DisruptionRow.module.css";

interface Props {
  item: DisruptionItem;
  /** Contenu posé avant le badge — la pastille de ligne dans le sélecteur, rien ailleurs. */
  leading?: ReactNode;
}

/** Une perturbation, partagée par le sélecteur de lignes et la fiche station. */
export function DisruptionRow({ item, leading }: Props) {
  // Chaque ligne possède son état : le parent n'a pas à tenir un registre des détails ouverts.
  const [open, setOpen] = useState(false);
  const severity = severityStyle(item.severity);
  // `leading` n'est jamais autre chose que la pastille de ligne : sa présence EST la condition.
  const title = disruptionTitle(item.title, leading != null);
  return (
    <li className={styles.row} data-severity={item.severity}>
      {leading}
      <span className={styles.text}>
        <span className={styles.badge}>
          {badgeText(item.shortMessage, severity.label)}
        </span>
        {/* Cliquable seulement s'il y a un détail à révéler — sinon le curseur mentirait. */}
        {item.detail ? (
          <button
            onClick={() => setOpen((current) => !current)}
            aria-expanded={open}
            className={styles.toggle}
          >
            {title}{open ? " ▾" : " ▸"}
          </button>
        ) : (
          <span className={styles.title}>{title}</span>
        )}
        {open && <div className={styles.detail}>{item.detail}</div>}
      </span>
    </li>
  );
}
```

- [ ] **Step 3: Vérifier**

Run: `cd frontend && npx vitest run src/ui/DisruptionRow.test.tsx src/ui/StopPanel.test.tsx src/ui/LinePicker.test.tsx && npx tsc -b && npm run lint && npm run build`
Expected: verts. `DisruptionRow` est rendue par `StopPanel` et `LinePicker` : les trois fichiers
doivent passer.

- [ ] **Step 4: Commit**

```bash
git add frontend/src/ui/DisruptionRow.tsx frontend/src/ui/DisruptionRow.module.css
git commit -m "style(qua-8): rangée de perturbation en CSS Modules, gravité par data-severity"
```

---

### Task 13: Conversion — `LineBadge` extrait, `LinePicker` converti, `severity` perd sa couleur

**Files:**
- Create: `frontend/src/ui/LineBadge.tsx`, `frontend/src/ui/LinePicker.module.css`
- Modify: `frontend/src/ui/LinePicker.tsx` (entier), `frontend/src/ui/severity.ts:8-13`,
  `frontend/src/ui/severity.test.ts:5-28`

**Interfaces:**
- Consumes: `lineBadge` de `./shared.module.css`, `--sev` de `index.css`.
- Produces: `LineBadge({ color: string, shortName: string, size: "s" | "m" })` — consommé aussi par
  `StopPanel` en tâche 14. `severityStyle(severity)` rend désormais `{ glyph, label }` :
  **plus de `color`**.

- [ ] **Step 1: Écrire `LineBadge.tsx`**

```tsx
import type { CSSProperties } from "react";
import shared from "./shared.module.css";

interface Props {
  /** Couleur officielle de la ligne, servie par `/network` : 16 teintes GTFS, ensemble non borné. */
  color: string;
  shortName: string;
  /** `s` dans le sélecteur (16 px), `m` en tête d'une perturbation et dans la fiche station. */
  size: "s" | "m";
}

/**
 * Pastille ronde d'une ligne. Extraite de ses trois copies à l'occasion de QUA-8 : garder le style
 * inline dupliqué était supportable, un module CSS dupliqué trois fois ne l'est pas.
 *
 * L'assertion sur `style` est nécessaire : `CSSProperties` ne connaît pas les variables CSS et
 * `tsc` refuse la propriété (TS2353) sans elle.
 */
export function LineBadge({ color, shortName, size }: Props) {
  return (
    <span
      className={shared.lineBadge}
      data-size={size}
      style={{ "--line-color": color } as CSSProperties}
    >
      {shortName}
    </span>
  );
}
```

- [ ] **Step 2: Écrire `LinePicker.module.css`**

```css
.list {
  margin: 6px 0 0;
  padding: 0;
  list-style: none;
}

.pills {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 8px;
}

/* `font-family` explicite : un bouton n'hérite pas de la police du document, et l'attribut
   remplacé posait `font: 12px sans-serif`. La bordure prend la teinte de gravité quand il y en a
   une (`--sev`, posé par `[data-severity]`), et retombe sinon sur le gris de contour. */
.pill {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 2px 6px;
  border: 1px solid var(--sev, var(--border));
  border-radius: 12px;
  background: var(--surface);
  font-family: var(--font);
  font-size: 12px;
  cursor: pointer;
  min-height: var(--tap);
}

/* Ligne exclue du filtre courant : atténuée, jamais retirée — on doit pouvoir la rallumer. */
.pill[data-shown="false"] {
  background: var(--surface-off);
  opacity: .45;
}

.glyph {
  color: var(--sev);
  font-weight: 700;
}
```

- [ ] **Step 3: Basculer `LinePicker.tsx`**

```tsx
import type { LineDisruptions, NetworkLine } from "../api/types";
import { severityStyle } from "./severity";
import { DisruptionRow } from "./DisruptionRow";
import { LineBadge } from "./LineBadge";
import styles from "./LinePicker.module.css";

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
  onToggle: (lineId: string) => void;
}

export function LinePicker({
  lines, disrupted, counts, disruptions, disruptionsOpen, visible, onToggle,
}: Props) {
  return (
    <>
      {disruptionsOpen && disrupted.length > 0 && (
        <ul className={styles.list}>
          {disrupted.flatMap((line) =>
            disruptions.get(line.id)!.items.map((item, index) => (
              <DisruptionRow
                key={`${line.id}-${index}`}
                item={item}
                leading={<LineBadge color={line.color} shortName={line.shortName} size="m" />}
              />
            )),
          )}
        </ul>
      )}
      <div className={styles.pills}>
        {lines.map((line) => {
          const shown = !visible || visible.has(line.id);
          const disruption = disruptions.get(line.id);
          const severity = disruption ? severityStyle(disruption.severity) : null;
          return (
            <button
              key={line.id}
              onClick={() => onToggle(line.id)}
              title={disruption
                ? `Ligne ${line.shortName} — ${severity!.label} : ${disruption.items.map((i) => i.title).join(" · ")}`
                : `${counts.get(line.id) ?? 0} train(s) sur la ligne ${line.shortName}`}
              className={styles.pill}
              data-shown={shown}
              data-severity={disruption?.severity}
            >
              <LineBadge color={line.color} shortName={line.shortName} size="s" />
              {counts.get(line.id) ?? 0}
              {severity && <span className={styles.glyph}>{severity.glyph}</span>}
            </button>
          );
        })}
      </div>
    </>
  );
}
```

Note : `data-severity={disruption?.severity}` vaut `undefined` sans perturbation, donc React
n'écrit pas l'attribut — `var(--sev, var(--border))` retombe alors sur le gris, et `.glyph` n'est
de toute façon pas rendu.

- [ ] **Step 4: Retirer `color` de `severity.ts`**

```ts
import type { Severity } from "../api/types";

/**
 * Glyphe ET libellé par gravité. Les deux, pas seulement l'un : deux lignes de métro partagent
 * déjà la même teinte (13/3bis, 6/7bis), et une information portée par la seule couleur est
 * illisible pour qui ne la distingue pas. La couleur, elle, vit dans `index.css` depuis QUA-8 :
 * `[data-severity]` la descend par la variable `--sev`.
 */
const STYLES: Record<Severity, { glyph: string; label: string }> = {
  BLOQUANTE: { glyph: "✕", label: "trafic bloqué" },
  PERTURBEE: { glyph: "!", label: "trafic perturbé" },
  INFORMATION: { glyph: "i", label: "information" },
  INCONNUE: { glyph: "?", label: "perturbation" },
};

export function severityStyle(severity: Severity) {
  return STYLES[severity] ?? STYLES.INCONNUE;
}
```

- [ ] **Step 5: Adapter `severity.test.ts`**

Remplacer les deux premiers tests (le troisième, sur le repli `INCONNUE`, reste inchangé) :

```ts
describe("severityStyle", () => {
  it("donne un glyphe ET un libellé à chaque gravité", () => {
    // Règle d'accessibilité du projet : jamais d'information portée par la seule couleur —
    // 13/3bis et 6/7bis partagent déjà leur teinte sur la carte. La couleur elle-même a rejoint
    // `index.css` avec QUA-8 (variable `--sev`), et n'est plus vérifiable ici.
    for (const severity of ["BLOQUANTE", "PERTURBEE", "INFORMATION", "INCONNUE"] as const) {
      const style = severityStyle(severity);
      expect(style.glyph).not.toBe("");
      expect(style.label).not.toBe("");
    }
  });

  it("distingue les gravités entre elles", () => {
    const severities = ["BLOQUANTE", "PERTURBEE", "INFORMATION", "INCONNUE"] as const;
    const styles = severities.map(severityStyle);
    expect(new Set(styles.map((s) => s.glyph))).toHaveLength(4);
    // Sans ce second axe, INFORMATION pourrait hériter du libellé de BLOQUANTE : `badgeText`
    // retombe sur ce libellé, un badge annoncerait « trafic bloqué » sur une simple information.
    expect(new Set(styles.map((s) => s.label))).toHaveLength(4);
  });
```

- [ ] **Step 6: Vérifier**

Run: `cd frontend && npx vitest run && npx tsc -b && npm run lint && npm run build`
Expected: tous verts. `tsc` est le garde-fou du retrait de `color` : toute lecture résiduelle de
`severityStyle(...).color` échouerait à la compilation.

- [ ] **Step 7: Commit**

```bash
git add frontend/src/ui/LineBadge.tsx frontend/src/ui/LinePicker.tsx frontend/src/ui/LinePicker.module.css frontend/src/ui/severity.ts frontend/src/ui/severity.test.ts
git commit -m "style(qua-8): pastille de ligne extraite, sélecteur converti, gravité sortie du JS"
```

---

### Task 14: Conversion — `StopPanel`

**Files:**
- Create: `frontend/src/ui/StopPanel.module.css`
- Modify: `frontend/src/ui/StopPanel.tsx:17-152`

**Interfaces:**
- Consumes: `LineBadge` (tâche 13), `linkButton` de `./shared.module.css`, `statusKind` de
  `./status`.
- Produces: rien de nouveau — mêmes props.

- [ ] **Step 1: Écrire `StopPanel.module.css`**

```css
.disruptions {
  margin: 0 0 4px;
  padding: 0;
  list-style: none;
}

.empty {
  margin: 4px 0;
  color: var(--text-muted);
}

.line {
  margin: 10px 0 0;
}

.lineHead {
  display: flex;
  align-items: center;
  gap: 6px;
}

/* La cible tactile est le bouton, transparent ; la pastille reste ronde dans son span. Porter
   `min-height` sur le carré de 18 px en faisait une ellipse verticale. Pas de `font: inherit` :
   ce bouton n'a aucun texte propre. */
.isolate {
  display: flex;
  align-items: center;
  justify-content: center;
  border: none;
  background: none;
  padding: 0;
  cursor: pointer;
  min-height: var(--tap);
}

.direction {
  margin: 4px 0 0 4px;
}

.destination {
  margin: 0 0 2px;
  font-weight: 600;
}

/* Rangée plutôt qu'empilement : à 44 px par cible tactile, trois passages empilés prenaient
   132 px par direction (retour recette). */
.passages {
  margin: 0 0 0 16px;
  padding: 0;
  list-style: none;
  display: flex;
  flex-wrap: wrap;
  align-items: center;
}

.passage {
  display: flex;
  align-items: center;
}

.time {
  composes: linkButton from "./shared.module.css";
  padding: 2px 4px;
  color: var(--accent);
  text-align: left;
}

/* Un passage supprimé affichait une heure indiscernable d'un train qui vient — le pire cas au
   regard de l'art. 5.7 (ne pas induire en erreur sur le contenu). */
.eta[data-cancelled="true"] {
  text-decoration: line-through;
}

/* Le séparateur suit l'horaire qu'il termine, jamais celui qu'il précède : sinon un repli le
   laissait orphelin en début de ligne. */
.separator {
  color: var(--separator);
}

.statusBadge {
  margin-left: 6px;
  padding: 0 5px;
  border-radius: 8px;
  background: var(--amber-bg-strong);
  color: var(--amber-text);
  font-weight: bold;
  font-size: 11px;
}

.statusBadge[data-kind="cancelled"] {
  background: var(--red-bg);
  color: var(--red-text);
}
```

- [ ] **Step 2: Basculer le composant**

Imports : ajouter `import { LineBadge } from "./LineBadge";` et
`import styles from "./StopPanel.module.css";`.

`StatusBadge` devient :

```tsx
/**
 * Un passage supprimé affichait une heure d'arrivée en bleu, indiscernable d'un train qui vient
 * — le pire cas au regard de l'art. 5.7 de la Licence Mobilité (ne pas induire en erreur sur le
 * contenu). Rien n'est affiché pour « à l'heure » : le silence dit déjà que tout va bien.
 */
function StatusBadge({ status }: { status: string }) {
  const kind = statusKind(status);
  if (kind !== "delayed" && kind !== "cancelled") {
    return null;
  }
  return (
    <span className={styles.statusBadge} data-kind={kind}>
      {statusLabel(status)}
    </span>
  );
}
```

Et le rendu principal (le calcul de `lines` en tête, avec son `eslint-disable-next-line
react-hooks/purity`, reste inchangé) :

```tsx
  return (
    <>
      {/* Perturbations visant les quais de cette station : c'est ce que l'anneau sur la carte a
          promis d'expliquer. Placé avant les passages — savoir que l'arrêt n'est pas desservi
          change la lecture des horaires qui suivent. */}
      {data.disruptions.length > 0 && (
        <ul className={styles.disruptions}>
          {data.disruptions.map((item, index) => (
            <DisruptionRow key={index} item={item} />
          ))}
        </ul>
      )}
      {lines.length === 0 && (
        <p className={styles.empty}>Aucun passage annoncé.</p>
      )}
      {lines.map((line) => (
        <div key={line.lineId} className={styles.line}>
          <div className={styles.lineHead}>
            <button
              onClick={() => onSelectLine?.(line.lineId)}
              // Isolement inconditionnel, comme un clic dans le sélecteur du bas : quel que
              // soit le filtre courant, ce clic ne laisse que cette ligne (décision produit).
              title={`N'afficher que la ligne ${line.shortName}`}
              aria-label={`N'afficher que la ligne ${line.shortName}`}
              className={styles.isolate}
            >
              <LineBadge color={line.color} shortName={line.shortName} size="m" />
            </button>
          </div>
          {line.directions.map((dir) => (
            // `destination` suffit comme clé : StationDepartureService.directionsOf groupe les
            // passages dans une Map<destination, …> PAR LIGNE, donc les destinations sont uniques
            // par construction au sein d'un LineDepartures — y compris sur une ligne à
            // embranchement. Y adjoindre l'index rendrait la clé instable dès qu'une destination
            // apparaît ou disparaît entre deux polls, et remonterait les sous-arbres pour rien.
            <div key={dir.destination} className={styles.direction}>
              <p className={styles.destination}>→ {dir.destination}</p>
              <ul className={styles.passages}>
                {dir.passages.map((p, index) => (
                  <li key={p.journeyRef} className={styles.passage}>
                    <button
                      onClick={() => onSelectTrain?.(p.journeyRef)}
                      className={styles.time}
                      title="Suivre ce métro"
                    >
                      <span
                        className={styles.eta}
                        data-cancelled={statusKind(p.status) === "cancelled"}
                      >
                        {formatEta(p.expectedTime)}
                      </span>
                      <StatusBadge status={p.status} />
                    </button>
                    {index < dir.passages.length - 1 && (
                      <span aria-hidden="true" className={styles.separator}>·</span>
                    )}
                  </li>
                ))}
              </ul>
            </div>
          ))}
        </div>
      ))}
    </>
  );
```

- [ ] **Step 3: Vérifier**

Run: `cd frontend && npx vitest run src/ui/StopPanel.test.tsx && npx tsc -b && npm run lint && npm run build`
Expected: verts. Le test « isole la ligne dont on clique la pastille » (tâche 5) vaut ici : il passe
par `aria-label`, que la conversion ne touche pas.

- [ ] **Step 4: Commit**

```bash
git add frontend/src/ui/StopPanel.tsx frontend/src/ui/StopPanel.module.css
git commit -m "style(qua-8): fiche station en CSS Modules, pastille partagée"
```

---

### Task 15: Conversion — `Sheet`, et réparation d'`isHidden` par le rouge

**Files:**
- Create: `frontend/src/ui/Sheet.module.css`
- Modify: `frontend/src/ui/Sheet.test.tsx:73-83` (`isHidden`), puis
  `frontend/src/ui/Sheet.tsx:214-313`

**Interfaces:**
- Consumes: tokens de la tâche 1, garde `[hidden]` de la tâche 1.
- Produces: la feuille expose `data-dragging` et la variable `--sheet-height` ; ses trois zones
  repliables portent `hidden`.

- [ ] **Step 1: Réparer `isHidden` d'abord — il doit rougir**

C'est l'ordre qui compte : le helper actuel ne lit que le `style.display` inline, donc masquer par
l'attribut `hidden` le laisserait vert sans rien vérifier (mesuré lors de la rédaction de QUA-8).
Le réparer avant la conversion prouve qu'il mord.

```tsx
// `getByText` ne voit que la présence dans le DOM, pas un repli posé sur un ancêtre : on remonte
// la chaîne jusqu'au `body`. Lit `hidden`, propriété du DOM, et non plus `style.display` : le
// repli n'est plus exprimé par du style inline depuis QUA-8, et un helper qui lisait le style
// devenait un faux vert (mesuré : masquer l'alerte par `hidden` laissait ces tests verts).
function isHidden(element: Element): boolean {
  let el: Element | null = element;
  while (el && el !== document.body) {
    if (el instanceof HTMLElement && el.hidden) {
      return true;
    }
    el = el.parentElement;
  }
  return false;
}
```

- [ ] **Step 2: Lancer pour constater le rouge**

Run: `cd frontend && npx vitest run src/ui/Sheet.test.tsx`
Expected: FAIL sur « garde l'alerte de gel et l'en-tête de fiche visibles, masque le résumé et le
corps » — le résumé et le corps sont encore masqués par `display: none` inline, qu'`isHidden` ne
lit plus.

- [ ] **Step 3: Écrire `Sheet.module.css`**

```css
.sheet {
  position: fixed;
  left: 0;
  right: 0;
  bottom: 0;
  /* Hauteur toujours numérique (mesurée au repli, dérivée du cran sinon) : une transition CSS
     n'anime jamais vers/depuis `auto`, ce qui rendait le repli instantané (résiduel A). La zone
     sûre s'ajoute par-dessus, comme aux autres crans : sinon elle la rognerait et l'aperçu
     perdrait sa ligne de résumé sur les iPhone récents. */
  height: calc(var(--sheet-height) + var(--safe-bottom));
  padding-bottom: var(--safe-bottom);
  box-sizing: border-box;
  display: flex;
  flex-direction: column;
  background: var(--surface);
  border-radius: 14px 14px 0 0;
  box-shadow: var(--shadow-sheet);
  font-size: 13px;
  transition: height 220ms ease-out;
}

/* Pendant un glissement, la hauteur suit le doigt : une transition la ferait courir après lui. */
.sheet[data-dragging="true"] {
  transition: none;
}

/* Regroupe tout ce qui échappe au repli, pour une mesure unique : observer la `<section>`
   elle-même boucherait dès qu'on fixerait sa hauteur d'après sa propre mesure. */
.peek {
  flex: 0 0 auto;
  display: flex;
  flex-direction: column;
}

.handle {
  flex: 0 0 auto;
  height: 44px;
  position: relative;
  border: none;
  background: none;
  padding: 0;
  cursor: grab;
  /* Sans ça, le navigateur traite le glissement vertical comme un défilement de page. */
  touch-action: none;
}

.grip {
  width: 36px;
  height: 4px;
  border-radius: 2px;
  background: var(--handle);
  margin: 0 auto;
}

/* `font-family` explicite : ce texte vit dans un `<button>`, qui n'hérite pas de la police du
   document. L'attribut remplacé posait `font: 10px sans-serif`. */
.asOf {
  position: absolute;
  right: 12px;
  top: 50%;
  transform: translateY(-50%);
  font-family: var(--font);
  font-size: 10px;
  color: var(--text-faint);
}

/* Les trois bandes à padding horizontal : alerte, en-tête de fiche, résumé. Pas de padding sur
   `.peek` lui-même — les paddings horizontaux restent portés par chaque zone, comme avant. */
.zone {
  flex: 0 0 auto;
  padding: 0 12px;
}

.body {
  flex: 1 1 auto;
  overflow-y: auto;
  overscroll-behavior: contain;
  padding: 0 12px 12px;
}

.footer {
  flex: 0 0 auto;
  padding: 0 12px 12px;
}
```

- [ ] **Step 4: Basculer `Sheet.tsx`**

Ajouter `import styles from "./Sheet.module.css";` et le type `CSSProperties` à l'import de React
(`type CSSProperties`), puis remplacer tout le `return` :

```tsx
  return (
    <section
      aria-label={label}
      className={styles.sheet}
      data-dragging={dragged !== null}
      // L'assertion est nécessaire : `CSSProperties` ne connaît pas les variables CSS et `tsc`
      // refuse la propriété (TS2353) sans elle.
      style={{ "--sheet-height": `${peeking ? peekHeight : height}px` } as CSSProperties}
    >
      <div ref={peek} className={styles.peek}>
        <button
          onPointerDown={onHandlePointerDown}
          onPointerMove={onHandlePointerMove}
          onPointerUp={onHandlePointerUp}
          onPointerCancel={onHandlePointerUp}
          onClick={onHandleClick}
          aria-expanded={cran !== "apercu"}
          aria-label="Changer la hauteur du panneau"
          className={styles.handle}
        >
          <div className={styles.grip} />
          {asOf && (
            // Fraîcheur de la donnée (art. 5.7) : ne peut pas rejoindre le « ⓘ », MapLibre fige son
            // texte à la construction du contrôle. Décoratif pour le lecteur d'écran : l'info vit
            // aussi dans SheetFooter (crans ouverts) et dans le « ⓘ » (nature de la donnée).
            <span aria-hidden="true" className={styles.asOf}>
              estimé {new Date(asOf).toLocaleTimeString("fr-FR", { hour: "2-digit", minute: "2-digit" })}
            </span>
          )}
        </button>
        {/* Pas de `hidden` ici, contrairement au résumé/pied voisins : une alerte de gel doit
            rester visible même au cran apercu (retour recette, correctif 1). */}
        <div className={styles.zone}>{alert}</div>
        {/* Idem : sans lui, une fiche ouverte devient muette (plus de titre, plus de « ✕ ») une
            fois la feuille repliée (résiduel C). */}
        <div className={styles.zone}>{header}</div>
      </div>
      <div className={styles.zone} hidden={peeking}>
        {summary}
      </div>
      <div
        ref={content}
        onPointerDown={onBodyPointerDown}
        onPointerMove={onBodyPointerMove}
        onPointerUp={onBodyPointerUp}
        onPointerCancel={onBodyPointerUp}
        className={styles.body}
        // Masqué plutôt que démonté : l'état des composants survit, et les 16 pastilles sortent
        // de l'ordre de tabulation au lieu de rester focusables hors écran.
        hidden={peeking}
      >
        {children}
      </div>
      <div className={styles.footer} hidden={peeking}>
        {footer}
      </div>
    </section>
  );
```

Mettre également à jour le commentaire de `peeking` (ligne ~67) : « voir les `hidden` ci-dessous »
au lieu de « voir les `display: peeking ? "none" : undefined` ci-dessous ».

- [ ] **Step 5: Lancer pour constater le vert, pour la bonne raison**

Run: `cd frontend && npx vitest run src/ui/Sheet.test.tsx`
Expected: PASS (17 tests). Le test du repli passe désormais parce que les zones portent réellement
`hidden`, pas parce que le helper ne regardait rien.

- [ ] **Step 6: Vérifier l'ensemble**

Run: `cd frontend && npx vitest run && npx tsc -b && npm run lint && npm run build`
Expected: tous verts, build réussi. Vérifier aussi qu'aucun attribut `style` porteur de règle ne
subsiste : `grep -rn "style={{" src --include=*.tsx` ne doit rendre que `LineBadge.tsx` et
`Sheet.tsx`, chacun avec sa seule variable CSS.

- [ ] **Step 7: Commit**

```bash
git add frontend/src/ui/Sheet.tsx frontend/src/ui/Sheet.module.css frontend/src/ui/Sheet.test.tsx
git commit -m "style(qua-8): feuille repliable en CSS Modules, repli par hidden"
```

---

### Task 16: Clôture — vérifications, recette, documentation

**Files:**
- Modify: `CLAUDE.md` (section « Conventions de code », limitation `--tap`)
- Modify: `docs/roadmap.md:57,83` (QUA-8 et UX-4)

**Interfaces:**
- Consumes: l'état livré par les tâches 1 à 15.
- Produces: la documentation à jour, et la liste de recette remise au développeur.

- [ ] **Step 1: Vérification complète**

Run: `cd frontend && npx vitest run && npm run lint && npm run build && ls dist/assets/ | grep -E "maplibre-gl-worker|\.css$"`
Expected: 76+ tests verts (69 avant QUA-8, plus le filet), lint muet, build réussi, `dist/assets/`
contenant le worker MapLibre **et** au moins un `.css` — la sortie du style inline déplace du poids
dans la feuille émise, son absence signalerait un import de module non résolu.

- [ ] **Step 2: Vérifier qu'aucune règle inline ne subsiste**

Run: `cd frontend && grep -rn "style={{" src --include=*.tsx`
Expected: exactement deux occurrences — `LineBadge.tsx` (`--line-color`) et `Sheet.tsx`
(`--sheet-height`). Toute autre occurrence est un reste à convertir.

- [ ] **Step 3: Recette navigateur — liste à passer par le développeur**

Aucun test ne voit le CSS (contrainte globale) : cette liste **est** le contrôle du rendu. À passer
sur la pile lancée par le développeur, en comparant si besoin avec `git stash` ou un second onglet
sur `master`.

*Au-dessus de 720 px :*
1. Panneau réseau en bas à gauche : padding et police inchangés, `70dvh` de fiche non atteint.
2. Fiche station (280 px) et fiche train (260 px), défilement au-delà de 70 % de la hauteur.
3. Pastilles de ligne rondes aux trois emplois (sélecteur 16 px, en-tête de perturbation 18 px,
   fiche station 18 px) — aucune ellipse verticale.
4. Une ligne masquée : fond gris et opacité réduite ; bordure colorée sur une ligne perturbée.
5. Liste de perturbations dépliée : badge plein coloré par gravité, glyphe, détail dépliable borné.
6. Passage supprimé barré, badges « supprimé » (rouge) et « retardé » (ambre).
7. Bouton « Suivre » : contour bleu inactif, négatif quand le suivi est actif.
8. Bandeau d'état : liseré bleu au chargement, ambre en erreur.
9. **Polices** : « tout afficher », les horaires, le chevron, les pastilles et le bouton « Suivre »
   gardent la même fonte qu'avant — c'est le point le plus exposé (un bouton n'hérite pas de la
   police du document).

*Sous 720 px :*
10. Les trois crans et la transition de 220 ms ; le glissement ne traîne pas derrière le doigt.
11. Glissement depuis la poignée **et** depuis le corps remonté en haut.
12. « estimé HH:MM » à droite de la poignée, à la bonne taille.
13. Au cran `apercu` : poignée, alerte de gel et en-tête de fiche visibles ; résumé, corps et pied
    disparus (c'est la garde `[hidden]`).
14. Cibles tactiles de 44 px ; `ⓘ` en haut à droite.

*Sur la pile Docker :*
15. Console vide de toute violation CSP, et `scripts/check-headers.sh` vert — attendu inchangé, la
    CSP n'a pas été touchée.

- [ ] **Step 4: Mettre à jour `CLAUDE.md`**

Trois retouches, dans la section « Conventions de code » :

- La limitation `--tap` dit « hors du style inline du projet » : remplacer par « demanderait une
  règle CSS visant leurs classes, dans `index.css` (les modules sont scopés) ».
- Ajouter, près de la ligne sur `feature-state` : « **Style : CSS Modules colocalisés** (`X.module.css`
  à côté de `X.tsx`), tokens de rôle et classes tierces dans `index.css`, motifs partagés dans
  `ui/shared.module.css` via `composes`. Ne subsistent en `style` inline que deux variables CSS
  (`--line-color`, `--sheet-height`) : une valeur venue de la donnée ou d'une mesure, jamais une
  règle — et il faut l'asserter `as CSSProperties`, `tsc` refusant les variables CSS. **Un `<button>`
  n'hérite pas de la police du document** : tout module qui en stylise un doit poser `font: inherit`
  ou `font-family`. **Tout masquage passe par l'attribut `hidden`**, jamais par `display: none` : la
  garde `[hidden] { display: none !important }` d'`index.css` est indispensable, la feuille de l'UA
  étant écrasée par n'importe quelle règle auteur. »
- Si la mesure de la tâche 6 a montré que jsdom 27 transmet `clientY`, réduire la phrase sur
  `firePointer` à sa seule raison subsistante (le contrôle de `timeStamp`).

- [ ] **Step 5: Mettre à jour `docs/roadmap.md`**

- Ligne QUA-8 : statut `fait`, avec le bilan — mécanique retenue, le filet ajouté (six fichiers de
  test, `LinePicker` n'en avait aucun), `isHidden` réparé, `LineBadge` extrait, et **la correction
  mesurée** : `style-src-attr 'none'` ne dépend pas de ce chantier (React comme MapLibre mutent le
  CSSOM, que la CSP ne gouverne pas), donc le seul gain réel était de débloquer UX-4.
- Ligne UX-4 : retirer la mention « styles inline sans thème sombre » comme blocage, et noter que
  les tokens de rôle et `[data-severity]` sont en place, le thème sombre se réduisant à redéfinir
  les variables sous `prefers-color-scheme: dark`.
- Section « Ordre recommandé », point 2 : QUA-8 passe dans la liste des faits, UX-4 devient le
  prochain chantier.

- [ ] **Step 6: Commit et fusion**

```bash
git add CLAUDE.md docs/roadmap.md
git commit -m "docs(qua-8): conventions de style, bilan et correction du gain CSP annoncé"
```

Puis, la recette de l'étape 3 étant propre, fusionner la branche sur `master` (la décision de
fusion appartient au développeur — cf. superpowers:finishing-a-development-branch).

---

## Self-Review

**Couverture de la spec :** § 1 (critères) → tâches 15 étape 6 et 16 étapes 1-2 ; § 2.1 (tests
aveugles au CSS) → contrainte globale + recette ; § 2.2 (`composes`) → tâche 11 étape 3, avec son
repli ; § 2.3 (`hidden`) → tâches 1 et 15 ; § 2.4 (CSP) → tâche 16 étapes 3 et 5 ; § 3 (trois
couches, tokens, `LineBadge`, `FloatingCard`) → tâches 1, 9, 13 ; § 4 (deux variables, table des
`data-*`) → tâches 8, 10, 12, 13, 14, 15 ; § 5 (filet en deux temps) → tâches 2-6 (invariants) et
10, 13, 15 (états observables) ; § 6 (ordre) → ordre des tâches ; § 7 (recette) → tâche 16 étape 3 ;
§ 9 (documentation) → tâche 16 étapes 4-5.

**Points corrigés en relecture :** la tâche 9 proposait d'abord `data-kind` sur `FloatingCard`, qui
ne relaie pas d'attributs arbitraires — remplacé par deux classes (`ficheStation`, `ficheTrain`), la
variable `ficheWidth` étant alors supprimée. Le retrait de `color` de `severityStyle` est placé en
tâche 13, après la migration de son dernier consommateur, pas en tâche 12.

**Cohérence des noms :** `styles` pour le module local, `shared` pour le module partagé,
`severity` pour l'ancienne variable `style` de `LinePicker`/`DisruptionRow` ;
`LineBadge({ color, shortName, size })` identique en tâches 13 et 14 ; `--sev` posé par
`[data-severity]` en tâche 1 et consommé en tâches 12 et 13.
