# UX-5b — Permalien / état dans l'URL : conception

Chantier UX-5b de la [feuille de route](../../roadmap.md). Conception arrêtée le **2026-08-21**,
deuxième des cinq sous-chantiers issus du découpage d'UX-5 (cf. [spec UX-5a](2026-08-20-ux-5a-recherche-station-design.md) § intro).

## 1. Objectif et critère de réussite

Pouvoir partager un lien qui restaure l'état de sélection courant : la station ouverte, le train
suivi, ou le sous-ensemble de lignes affiché. Aujourd'hui `App.tsx` ne porte aucun routing — tout
est `useState` local, perdu à chaque rechargement ou partage d'URL.

Critère de réussite : copier l'URL affichée pendant qu'une station est ouverte (ou un train suivi,
ou un filtre de lignes actif), l'ouvrir dans un nouvel onglet, obtenir exactement le même état
visuel — fiche ouverte, caméra recentrée le cas échéant, lignes filtrées.

## 2. Ce qui décide de la conception

Décisions prises en brainstorming, qui ne sont pas rediscutées ici :

- **Portée** : les trois éléments de sélection sont permalinkables — station sélectionnée, train
  suivi, filtre de lignes visibles.
- **Format d'URL** : query params (`?station=...&train=...&lines=...`), pas de segments de chemin
  — le projet n'a qu'une seule page, un routing par chemin n'apporterait rien et poserait la
  question d'un 404 au rechargement selon la configuration du serveur statique.
- **Historique navigateur** : `history.replaceState` uniquement. Aucune sélection ne crée
  d'entrée d'historique — le bouton Précédent quitte l'appli comme aujourd'hui, il ne navigue pas
  entre sélections successives. Pas de `pushState`, pas d'écoute `popstate`.
- **Recentrage au chargement** : un lien vers une station recentre la caméra dessus, comme un clic
  direct — même expérience qu'une sélection normale, pas une fiche ouverte hors-champ.
- **Identifiant inconnu** (station, train) : ignoré silencieusement, l'appli démarre dans l'état
  par défaut sur ce point précis. Cohérent avec la décision produit déjà actée pour les données
  temps réel (CLAUDE.md, § « Données temps réel ») : ne jamais faire planter l'UI sur une donnée
  absente ou périmée.

## 3. Pas de nouvelle dépendance de routing

Aucune librairie de routing n'est ajoutée. Trois `useState` suffisent à représenter l'état
permalinkable (`selectedStationId`, `selectedJourneyRef`, `visibleLines`), et le format retenu
(query params, `replaceState` seul) se manipule entièrement avec `URLSearchParams` et
`history.replaceState`, déjà fournis par le navigateur. Une lib comme `react-router` résoudrait un
problème que ce chantier n'a pas — plusieurs pages, navigation par pile d'historique — au prix
d'une dépendance et d'une restructuration de `App.tsx` sans rapport avec l'objectif.

## 4. Module pur : encodage / décodage

Nouveau fichier `frontend/src/api/permalink.ts`, sans dépendance à React ni au DOM — testable en
Node comme `ui/toggleLine.ts` ou `ui/color.ts`.

```ts
export interface PermalinkState {
  stationId: string | null;
  journeyRef: string | null;
  visibleLineIds: string[] | null; // null = toutes les lignes
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
    // Un lien trafiqué à la main peut porter les deux : la station l'emporte, au même titre
    // qu'un clic carte ferme le suivi d'un train (App.tsx, selectStation).
    journeyRef: stationId ? null : params.get("train"),
    visibleLineIds: linesParam ? linesParam.split(",").filter(Boolean) : null,
  };
}
```

**Ce que ce module ne fait pas** : valider qu'un id existe réellement dans le réseau courant. Il ne
connaît pas `network` — c'est une décision syntaxique (quelle forme prend l'URL), pas sémantique
(cet id est-il valide). La validation sémantique, quand il y en a une, vit dans `App.tsx` (§ 6).

`visibleLineIds` ne peut jamais être un tableau vide en écriture : `toggleLine`
(`ui/toggleLine.ts:21-23`) refuse déjà de vider la carte d'un clic, donc `visibleLines` en mémoire
est soit `null` (toutes), soit un `Set` non vide. En lecture, un `lines=` vide ou absent décode en
`null` sans distinction — les deux signifient « toutes les lignes ».

## 5. Écriture : état → URL

Un seul `useEffect` dans `App.tsx`, à côté des effets existants :

```ts
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

**Pourquoi `urlRestored`** : au tout premier rendu, `selectedStationId` vaut encore `null` — sa
restauration depuis l'URL est asynchrone (§ 6, elle attend `map` et `network`). Sans ce garde,
cet effet s'exécuterait immédiatement au montage et effacerait un `?station=...` présent dans
l'URL avant que la restauration ait eu lieu à le lire. `urlRestored` démarre à `true` s'il n'y
avait rien à restaurer (pas de `station` dans l'URL au chargement), et sinon passe à `true` une
fois que l'effet de restauration a fini — qu'il ait abouti ou ignoré un id introuvable.

## 6. Lecture : URL → état, au montage

**Train et filtre de lignes** : synchrones, seedés directement dans l'initialiseur de `useState`
existant, sans effet dédié :

```ts
const initialPermalink = useRef(decodePermalink(window.location.search)).current;
const [selectedJourneyRef, setSelectedJourneyRef] = useState<string | null>(initialPermalink.journeyRef);
const [follow, setFollow] = useState(initialPermalink.journeyRef !== null);
const [visibleLines, setVisibleLines] = useState<Set<string> | null>(
  initialPermalink.visibleLineIds ? new Set(initialPermalink.visibleLineIds) : null,
);
```

`follow` démarre à `true` si un train est restauré : même comportement qu'un clic direct sur un
train sur la carte (`onClick` de la couche `vehicles`, `App.tsx:163-164`, pose déjà
`setFollow(true)`). Si la référence a disparu du flux SIRI depuis (probable sur un vieux lien),
`useVehicles` ne rappelle jamais `onSelected` pour cette référence, `selected` reste `null`,
aucune fiche ne s'affiche — silencieux, pas de crash, comportement déjà existant de
`useVehicles`.

**Filtre de lignes** : pas de validation contre `network.lines`. Un id inconnu dans `lines=` ne
matchera simplement rien dans le pipeline de filtrage existant (`useNetwork`), sans exception. Si
*tous* les ids d'un lien sont invalides, la carte affiche « aucune ligne » plutôt que de revenir à
« toutes » — cas limite accepté (lien très périmé après un changement de périmètre réseau),
l'utilisateur a le bouton « tout afficher » de `NetworkSummary` sous la main pour s'en sortir.

**Station** : seule restauration asynchrone, car elle a besoin de `map` (pour `setFilter`,
`easeTo`) et de `network` (pour retrouver les coordonnées de la station et recentrer la caméra,
§ 2). Un effet gaté sur `[map, network]`, protégé par un ref pour ne s'exécuter qu'une fois :

```ts
const stationRestored = useRef(false);
const [urlRestored, setUrlRestored] = useState(initialPermalink.stationId === null);

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
  setUrlRestored(true);
}, [map, network]);
```

Si `/network` ne charge jamais (panne backend persistante), cet effet ne se déclenche jamais et la
station ne se restaure jamais — dégradation cohérente avec le reste de l'appli, aucune erreur
affichée. `setUrlRestored(true)` est appelé dans tous les cas (id trouvé, id introuvable, ou pas
de station à restaurer du tout mais l'effet s'exécute quand même une fois `map`/`network` prêts)
pour débloquer l'effet d'écriture (§ 5).

## 7. Filet de tests

**`permalink.ts`** (`permalink.test.ts`, pur, Node, pas de pragma jsdom) :
- Aller-retour encode → decode sur les trois champs, ensemble et séparément.
- État par défaut (les trois `null`) encode en chaîne vide.
- Plusieurs lignes : `lines=1,4,9` décode en `["1", "4", "9"]`.
- `station` et `train` tous deux présents dans la query : `station` gagne, `journeyRef` décode à
  `null`.
- `lines=` vide ou absent décode à `null` dans les deux cas.

**`App.tsx`** : les deux effets ajoutés (§ 5, § 6) **ne sont pas couverts par Vitest** —
`App.tsx` est déjà exclu du harnais de tests de composants car il construit un vrai MapLibre
(limitation documentée dans CLAUDE.md). Vérification uniquement au navigateur, listée dans le
point de recette (§ 9).

## 8. Hors périmètre, et pourquoi

- **Historique navigable (Précédent/Suivant entre sélections)** : tranché en § 2, `replaceState`
  seul suffit à l'objectif de partage.
- **Validation sémantique du filtre de lignes** : tranché en § 6, la dégradation silencieuse est
  acceptée plutôt que d'ajouter un effet de validation supplémentaire pour un cas rare.
- **Message d'erreur sur lien périmé** : tranché en brainstorming (§ 2), ignorer silencieusement.
- **Recherche de station, géolocalisation, sens des tracés, plus de passages** : les quatre autres
  moitiés d'UX-5 (UX-5a fait, UX-5c à UX-5e), hors périmètre de ce chantier.

## 9. Ordre d'exécution

1. `frontend/src/api/permalink.ts` (TDD, module pur) : encode/decode, les cinq cas du § 7.
2. Câblage dans `App.tsx` : seeding synchrone (train, lignes) dans les `useState` existants, puis
   l'effet de restauration de station (§ 6), puis l'effet d'écriture gaté sur `urlRestored`
   (§ 5).
3. Recette navigateur : copier l'URL avec une station ouverte / un train suivi / un filtre de
   lignes actif, l'ouvrir dans un nouvel onglet, vérifier la restauration ; vérifier qu'un id
   inconnu dans chaque paramètre ne casse rien ; vérifier que le bouton Précédent quitte l'appli
   sans naviguer entre sélections.
