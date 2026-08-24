# UX-5b — Permalien / état dans l'URL : plan d'implémentation

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Encoder la sélection courante (station ouverte, train suivi, filtre de lignes visibles)
dans l'URL, pour qu'un lien copié restaure exactement le même état à l'ouverture.

**Architecture:** Un module pur `api/permalink.ts` (encode/decode d'une query string), câblé dans
`App.tsx` via deux `useEffect` — un de lecture au montage (restaure depuis l'URL), un d'écriture en
continu (`history.replaceState` à chaque changement de sélection). Aucune librairie de routing.

**Tech Stack:** React 19 / TypeScript 6 / Vitest 4, `URLSearchParams` et `history.replaceState`
natifs du navigateur.

**Spec:** [docs/superpowers/specs/2026-08-21-ux-5b-permalink-design.md](../specs/2026-08-21-ux-5b-permalink-design.md)

## Global Constraints

- Format d'URL : query params (`station`, `train`, `lines`), pas de segments de chemin (spec § 2).
- `history.replaceState` uniquement — jamais `pushState`, jamais d'écoute `popstate` (spec § 2).
- Un lien vers une station recentre la caméra dessus, comme un clic direct (spec § 2).
- Id de station ou de train inconnu dans l'URL : ignoré silencieusement, état par défaut sur ce
  point précis — jamais d'erreur affichée (spec § 2, § 6).
- Si `station` et `train` sont tous deux présents dans l'URL, `station` gagne ; `train` est
  abandonné dès le décodage, avant toute logique React (spec § 4).
- Un id de ligne inconnu dans `lines=` n'est **pas** validé contre le réseau : aucun effet dédié,
  la dégradation (rien ne matche) est acceptée telle quelle (spec § 6).
- `visibleLineIds` ne peut jamais être un tableau vide au décodage : `lines=` vide ou absent
  décodent tous les deux en `null` (« toutes les lignes »), au même titre qu'en mémoire
  `visibleLines` est soit `null` soit un `Set` non vide (`ui/toggleLine.ts:21-23`).
- Restaurer un train depuis l'URL pose `follow` à `true` — même comportement qu'un clic direct sur
  un train sur la carte (`App.tsx:163-164`).
- Commits au format déjà utilisé par le dépôt : `type(ux-5b): message`.

---

## Task 1 : Module pur `api/permalink.ts` (TDD)

**Files:**
- Create: `frontend/src/api/permalink.ts`
- Create: `frontend/src/api/permalink.test.ts`

**Interfaces:**
- Produces: `interface PermalinkState { stationId: string | null; journeyRef: string | null;
  visibleLineIds: string[] | null }` ; `encodePermalink(state: PermalinkState): string` (rend une
  query string commençant par `?`, ou une chaîne vide) ; `decodePermalink(search: string):
  PermalinkState`.

- [ ] **Step 1: Écrire le test qui échoue**

Créer `frontend/src/api/permalink.test.ts` :

```ts
import { describe, expect, it } from "vitest";
import { decodePermalink, encodePermalink } from "./permalink";

describe("encodePermalink", () => {
  it("rend une chaîne vide pour l'état par défaut", () => {
    expect(encodePermalink({ stationId: null, journeyRef: null, visibleLineIds: null })).toBe("");
  });

  it("encode une station seule", () => {
    expect(encodePermalink({ stationId: "ST1", journeyRef: null, visibleLineIds: null }))
      .toBe("?station=ST1");
  });

  it("encode un train seul", () => {
    expect(encodePermalink({ stationId: null, journeyRef: "SIRI:1", visibleLineIds: null }))
      .toBe("?train=SIRI%3A1");
  });

  it("encode le filtre de lignes, plusieurs lignes séparées par une virgule", () => {
    expect(encodePermalink({ stationId: null, journeyRef: null, visibleLineIds: ["1", "4", "9"] }))
      .toBe("?lines=1%2C4%2C9");
  });

  it("station et lignes se combinent dans la même query", () => {
    expect(encodePermalink({ stationId: "ST1", journeyRef: null, visibleLineIds: ["9"] }))
      .toBe("?station=ST1&lines=9");
  });

  it("préfère la station au train si les deux sont fournis", () => {
    expect(encodePermalink({ stationId: "ST1", journeyRef: "SIRI:1", visibleLineIds: null }))
      .toBe("?station=ST1");
  });
});

describe("decodePermalink", () => {
  it("décode l'absence de paramètres en état par défaut", () => {
    expect(decodePermalink("")).toEqual({ stationId: null, journeyRef: null, visibleLineIds: null });
  });

  it("fait l'aller-retour sur une station seule", () => {
    const encoded = encodePermalink({ stationId: "ST1", journeyRef: null, visibleLineIds: null });
    expect(decodePermalink(encoded)).toEqual({ stationId: "ST1", journeyRef: null, visibleLineIds: null });
  });

  it("fait l'aller-retour sur un train seul", () => {
    const encoded = encodePermalink({ stationId: null, journeyRef: "SIRI:1", visibleLineIds: null });
    expect(decodePermalink(encoded)).toEqual({ stationId: null, journeyRef: "SIRI:1", visibleLineIds: null });
  });

  it("fait l'aller-retour sur plusieurs lignes", () => {
    const encoded = encodePermalink({ stationId: null, journeyRef: null, visibleLineIds: ["1", "4", "9"] });
    expect(decodePermalink(encoded)).toEqual({ stationId: null, journeyRef: null, visibleLineIds: ["1", "4", "9"] });
  });

  it("station et train tous deux présents : la station gagne, le train est abandonné", () => {
    expect(decodePermalink("?station=ST1&train=SIRI:1"))
      .toEqual({ stationId: "ST1", journeyRef: null, visibleLineIds: null });
  });

  it("lines vide décode en null, comme lines absent", () => {
    expect(decodePermalink("?lines=")).toEqual({ stationId: null, journeyRef: null, visibleLineIds: null });
    expect(decodePermalink("")).toEqual({ stationId: null, journeyRef: null, visibleLineIds: null });
  });
});
```

- [ ] **Step 2: Vérifier que ça échoue**

Run: `cd frontend && npx vitest run src/api/permalink.test.ts`
Expected: échec — `./permalink` n'existe pas.

- [ ] **Step 3: Implémenter `permalink.ts`**

Créer `frontend/src/api/permalink.ts` :

```ts
/**
 * État de sélection partageable par lien (UX-5b) : station ouverte, train suivi, filtre de
 * lignes visibles. Module pur, sans dépendance à React ni au DOM (hormis `URLSearchParams`,
 * fourni par l'environnement d'exécution) — la validation sémantique (un id existe-t-il vraiment
 * dans le réseau courant ?) vit dans App.tsx, pas ici : ce module ne connaît que la syntaxe de
 * l'URL.
 */
export interface PermalinkState {
  stationId: string | null;
  journeyRef: string | null;
  /** `null` = toutes les lignes. Jamais un tableau vide : cf. ui/toggleLine.ts. */
  visibleLineIds: string[] | null;
}

export function encodePermalink(state: PermalinkState): string {
  const params = new URLSearchParams();
  if (state.stationId) {
    params.set("station", state.stationId);
  } else if (state.journeyRef) {
    params.set("train", state.journeyRef);
  }
  if (state.visibleLineIds) {
    params.set("lines", state.visibleLineIds.join(","));
  }
  const query = params.toString();
  return query ? `?${query}` : "";
}

export function decodePermalink(search: string): PermalinkState {
  const params = new URLSearchParams(search);
  const stationId = params.get("station");
  const linesParam = params.get("lines");
  return {
    stationId,
    // Un lien composé à la main peut porter les deux : la station l'emporte, au même titre
    // qu'un clic carte ferme le suivi d'un train (App.tsx, selectStation).
    journeyRef: stationId ? null : params.get("train"),
    visibleLineIds: linesParam ? linesParam.split(",").filter(Boolean) : null,
  };
}
```

- [ ] **Step 4: Vérifier que les tests passent**

Run: `cd frontend && npx vitest run src/api/permalink.test.ts`
Expected: tous les tests passent.

- [ ] **Step 5: Lint et typage**

Run: `cd frontend && npm run lint && npm run build`
Expected: les deux muets/verts.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/api/permalink.ts frontend/src/api/permalink.test.ts
git commit -m "feat(ux-5b): encodage/decodage pur de l'etat partageable par lien"
```

---

## Task 2 : Câblage dans `App.tsx`, recette et clôture

**Files:**
- Modify: `frontend/src/App.tsx`
- Modify: `docs/roadmap.md`

**Interfaces:**
- Consumes: `decodePermalink`, `encodePermalink`, `PermalinkState` (Task 1) ; `selectStation`
  (`App.tsx:121-146`, déjà existant, non modifié) ; `network.stations: NetworkStation[]`
  (`api/types.ts:15-21`, déjà existant).

Refactor + ajout à comportement vérifiable uniquement au navigateur : `App.tsx` est hors du
harnais Vitest (cf. Task 4 du plan UX-5a, et CLAUDE.md). La vérification automatique se limite à
`npm run build` (typage) ; le reste est une recette manuelle (Step 5).

- [ ] **Step 1: Ajouter l'import et la lecture initiale de l'URL**

Dans `frontend/src/App.tsx`, ajouter l'import après celui de `VehicleFeatureProperties`
(après la ligne 26) :

```tsx
import { decodePermalink, encodePermalink } from "./api/permalink";
```

Juste après `const departuresAbort = useRef<AbortController | null>(null);` (ligne 36), avant
la déclaration de `selected` (ligne 37), ajouter :

```tsx
  // Lu une seule fois, à la création du composant : les changements ultérieurs de l'URL
  // (navigation externe) ne sont pas suivis, seul `replaceState` (plus bas) l'écrit depuis
  // l'état de l'appli — jamais l'inverse après ce point.
  const initialPermalink = useRef(decodePermalink(window.location.search)).current;
```

- [ ] **Step 2: Seeder les états synchrones depuis l'URL**

Dans le même fichier, remplacer les trois lignes (actuellement 38, 39, 42) :

```tsx
  const [selectedJourneyRef, setSelectedJourneyRef] = useState<string | null>(null);
  const [follow, setFollow] = useState(false);
```

par :

```tsx
  const [selectedJourneyRef, setSelectedJourneyRef] = useState<string | null>(initialPermalink.journeyRef);
  // Même comportement qu'un clic direct sur un train sur la carte (onClick de la couche
  // `vehicles`, plus bas dans ce fichier, qui pose déjà `setFollow(true)`).
  const [follow, setFollow] = useState(initialPermalink.journeyRef !== null);
```

et :

```tsx
  const [visibleLines, setVisibleLines] = useState<Set<string> | null>(null);
```

par :

```tsx
  const [visibleLines, setVisibleLines] = useState<Set<string> | null>(
    initialPermalink.visibleLineIds ? new Set(initialPermalink.visibleLineIds) : null,
  );
```

Juste en dessous de la ligne des `counts` (actuellement ligne 43), ajouter :

```tsx
  // Débloque l'effet d'écriture (plus bas) : vrai tout de suite s'il n'y avait pas de station à
  // restaurer, sinon posé par l'effet de restauration une fois qu'il a fini (id trouvé ou non).
  const [urlRestored, setUrlRestored] = useState(initialPermalink.stationId === null);
  const stationRestored = useRef(false);
```

- [ ] **Step 3: Ajouter l'effet de restauration de la station**

Dans le même fichier, juste après la fermeture de `selectStation` (après la ligne 146,
avant le `useEffect` qui pose les écouteurs de clic carte à la ligne 148), ajouter :

```tsx
  // Restauration d'un lien partagé (UX-5b) : la station a besoin de `map` (setFilter, easeTo) et
  // de `network` (retrouver ses coordonnées pour recentrer la caméra, comme un clic direct). Le
  // ref empêche de rejouer la restauration si `map`/`network` changent à nouveau plus tard.
  useEffect(() => {
    if (stationRestored.current || !map || !network) {
      return;
    }
    stationRestored.current = true;
    const target = initialPermalink.stationId
      ? network.stations.find((s) => s.id === initialPermalink.stationId)
      : undefined;
    if (target) {
      void selectStation(map, target.id, [target.lng, target.lat]);
    }
    // Débloque l'écriture même si l'id était introuvable : l'URL doit refléter l'état réel
    // (par défaut), pas rester bloquée sur un id qui ne sera jamais restauré.
    setUrlRestored(true);
  }, [map, network]);
```

- [ ] **Step 4: Ajouter l'effet d'écriture (état → URL)**

Juste après l'effet ajouté au Step 3, ajouter :

```tsx
  // Écrit l'état de sélection dans l'URL à chaque changement, pour qu'elle reste copiable à tout
  // moment (UX-5b). `replaceState` seul, jamais `pushState` : le bouton Précédent doit continuer
  // à quitter l'appli, pas naviguer entre sélections (spec § 2).
  useEffect(() => {
    if (!urlRestored) {
      return;
    }
    const query = encodePermalink({
      stationId: selectedStationId,
      journeyRef: selectedJourneyRef,
      visibleLineIds: visibleLines ? [...visibleLines] : null,
    });
    window.history.replaceState(null, "", `${window.location.pathname}${query}`);
  }, [urlRestored, selectedStationId, selectedJourneyRef, visibleLines]);
```

- [ ] **Step 5: Vérifier le typage**

Run: `cd frontend && npm run lint && npm run build`
Expected: lint muet, build réussi, aucune erreur `tsc`.

- [ ] **Step 6: Recette navigateur manuelle (jsdom ne peut pas la remplacer)**

Lancer `npm run dev`, puis vérifier :
- Sélectionner une station sur la carte (ou via la recherche UX-5a) : l'URL affiche
  `?station=<id>` immédiatement. Copier cette URL, l'ouvrir dans un nouvel onglet : la même
  station s'ouvre, avec la caméra recentrée dessus, exactement comme le clic d'origine.
- Sélectionner un train (clic direct sur la carte) : l'URL affiche `?train=<ref>`. Ouvrir cette
  URL dans un nouvel onglet pendant que le train est toujours dans le flux SIRI : la fiche train
  s'ouvre et le suivi (`follow`) démarre — même rendu qu'un clic direct.
- Filtrer les lignes visibles (cliquer une pastille) : l'URL affiche `?lines=<id>`. Recharger la
  page sur cette URL : le même filtre est restauré.
- Modifier l'URL à la main avec un id de station inexistant (ex. `?station=zzz`) et recharger :
  l'appli démarre dans l'état par défaut, sans erreur affichée, sans crash.
- Modifier l'URL à la main avec `?station=ST1&train=SIRI:1` (les deux) et recharger : seule la
  station se restaure.
- Sélectionner successivement deux stations différentes, puis cliquer le bouton Précédent du
  navigateur : l'appli quitte la page (pas de navigation interne entre les deux sélections).

- [ ] **Step 7: Commit**

```bash
git add frontend/src/App.tsx
git commit -m "feat(ux-5b): restaurer et partager la selection via l'URL"
```

- [ ] **Step 8: Mettre à jour la roadmap**

Dans `docs/roadmap.md`, faire passer le statut de la ligne UX-5b de « à faire » à « fait », avec
un résumé bref de ce qui a été livré (module `permalink.ts`, les deux effets dans `App.tsx`,
`replaceState` seul) et des constats de la recette (Step 6).

```bash
git add docs/roadmap.md
git commit -m "docs(ux-5b): chantier permalien cloture"
```
