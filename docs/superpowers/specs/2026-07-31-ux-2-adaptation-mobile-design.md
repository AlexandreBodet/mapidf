# UX-2 — Adaptation mobile : feuille repliable et panneaux sans placement

*Document de conception — 2026-07-31*

Chantier [UX-2](../../roadmap.md) de la feuille de route. Rend l'application utilisable sur
téléphone, où elle ne l'est pas : quatre panneaux flottent à largeur fixe (260 à 360 px) et le
sélecteur de 16 lignes occupe le bas de l'écran.

## 1. Objectif et critères de réussite

Sur un écran étroit, l'application doit se consulter debout, d'une main, sur un quai.

Est réussi si, à 390 × 844 et à 360 × 640 :

1. La carte n'est jamais couverte par **deux** surfaces d'information à la fois, et reste
   manipulable dans tous les états. Le cran `plein` la masque presque entièrement, mais c'est un
   choix explicite de l'utilisateur, pas l'état par défaut.
2. Le sélecteur de lignes, les passages d'une station et la fiche d'un train sont tous
   atteignables sans dépassement d'écran ni défilement horizontal.
3. Toute cible tactile mesure au moins 44 px dans sa plus petite dimension.
4. Toucher une station produit un effet visible immédiatement, même feuille repliée.
5. **Aucune régression visible sur desktop.** Une nuance : sortir le titre et le `✕` des fiches
   remplace un `float: right` par une rangée flex (`alignItems: flex-start`, qui reproduit le
   comportement du float). Identique sur un titre d'une ligne ; à un cheveu près sur un nom de
   station qui passe à la ligne. C'est la seule différence acceptée.

## 2. État des lieux mesuré

| Fait | Conséquence sur écran étroit |
|---|---|
| `VehiclePanel` (260 px), `StopPanel` (280 px), `LinePicker` (300 px), `NetworkStatus` (360 px) se positionnent **eux-mêmes** en `position: absolute` | Aucun ne peut être déplacé sans être réécrit |
| `App` vide la sélection train à l'ouverture d'une station, et l'inverse ([App.tsx:104-123](../../../frontend/src/App.tsx#L104-L123)) | **Il n'y a jamais deux fiches simultanées** — fondement du choix de section 4 |
| Aucun fichier CSS dans le projet : 100 % de style inline | Aucune media query n'est exprimable aujourd'hui |
| `StopPanel` borne sa hauteur à `70vh` | `vh` ignore la barre d'outils repliable : le panneau dépasse le visible |
| Pastilles ~24 px de haut, `✕` ~28 px | Sous le seuil tactile de 44 px |
| Attribution MapLibre épinglée en bas à droite, `compact: false` | Exactement la place de la feuille |
| `VehicleLayer` recentre par `jumpTo({ center })` à chaque frame ([VehicleLayer.ts:420](../../../frontend/src/map/VehicleLayer.ts#L420)) ; le clic station fait un `easeTo({ center })` | Les deux centreraient derrière la feuille |

## 3. La carte ne rétrécit jamais

La feuille **flotte au-dessus** d'une carte qui conserve tout le viewport. Aucune mise en
colonne, donc aucun `map.resize()` et aucun recalcul de tuiles quand la feuille bouge.

Ce qui change à chaque cran, c'est ce que la caméra considère comme visible : un unique
`map.setPadding({ bottom })`. MapLibre en tient compte pour tous ses recentrages — le `easeTo`
du clic station comme le `jumpTo` par frame du suivi se posent d'eux-mêmes **au-dessus** de la
feuille, sans une ligne de changement dans `VehicleLayer`. Effet de bord favorable :
`getBounds()`, qui sert au culling des véhicules, colle désormais à la zone réellement vue.

`bottom` est **plafonné à 45 % de la hauteur du viewport** : au cran plein, un padding de 90 %
rendrait la géométrie de caméra absurde. Le padding retombe à 0 dès qu'on quitte le mode étroit.

## 4. Une seule feuille, contenu remplacé

La feuille est l'unique surface d'information en mode étroit. Contenu par défaut : le sélecteur
de lignes. Ouvrir une station ou un train y met la fiche correspondante.

**Pourquoi pas deux feuilles superposées** : `App` interdit déjà la coexistence d'une fiche
station et d'une fiche train. Deux surfaces empilées mettraient en scène une simultanéité que la
machine à états refuse, au prix d'un ordre d'empilement, de deux états de hauteur et d'un
arbitrage sur le destinataire du geste. Une feuille unique dit la vérité du modèle.

Conséquences :

- **Aucun état nouveau dans `App`** : `station` et `selected` existent et sont déjà exclusifs.
- **La hauteur choisie persiste** au changement de contenu.
- **Fermer une fiche reste le `✕` existant** : le sélecteur étant le contenu par défaut, fermer
  y revient. Aucun geste nouveau, aucun code supplémentaire.

## 5. Deux mécanismes de responsive

| Besoin | Outil | Pourquoi pas l'autre |
|---|---|---|
| **Structure** — feuille *ou* cartes flottantes | `useIsNarrow()` : `matchMedia("(max-width: 720px)")` | Une media query ne peut pas déplacer un composant d'un conteneur à un autre |
| **Dimensions** — cibles tactiles à 44 px | Variable CSS `--tap`, dans un `index.css` | Passer un booléen dans quatre composants les rendrait tous conscients du seuil |

`:root { --tap: 0px }`, redéfinie à `44px` sous le seuil. Les éléments interactifs portent
`minHeight: "var(--tap)"` : sans effet sur desktop, conformes au tactile en dessous. Aucun
panneau ne connaît le seuil.

Le seuil de 720 px est donc **écrit deux fois** — dans le hook et dans le CSS. Commenté des deux
côtés ; le générer coûterait plus cher que la duplication.

**Le seuil porte sur la largeur seule.** Un téléphone en paysage (844 × 390) garde les cartes
flottantes : une feuille sur 390 px de haut serait pire que le mal.

## 6. Les panneaux cessent de savoir où ils vivent

Geste central du chantier, bénéfique aussi au desktop : la même chrome (fond blanc, rayon,
ombre, `position: absolute`) est aujourd'hui recopiée dans quatre panneaux.

```
AVANT                               APRÈS
StopPanel    = placement + chrome    Sheet ────────┐
             + titre + contenu                     ├── PanelHeader + contenu
VehiclePanel = idem                 FloatingCard ──┘
LinePicker   = idem
```

`NetworkStatus` **reste autonome** : c'est un bandeau, pas un panneau — sa chrome diffère (liseré
de couleur, `pointerEvents: none`) et il n'a ni titre ni fermeture. Le faire passer par
`FloatingCard` n'ajouterait que des paramètres d'exception. Il ne reçoit que le correctif de
largeur de la section 10.

Composants créés :

- **`ui/Sheet.tsx`** — coquille présentationnelle : poignée, glissement, crans, zone sûre,
  défilement interne. **Ne sait rien de son contenu.** Contrôlée : `App` détient le cran.
- **`ui/FloatingCard.tsx`** — conteneur desktop, paramétré par un `anchor`
  (`top-right` | `bottom-left`). Supprime la chrome répétée dans les trois panneaux.
- **`ui/PanelHeader.tsx`** — titre et `✕`, sortis des fiches. Coiffe la carte sur desktop,
  voisine la poignée sur mobile.
- **`ui/NetworkSummary.tsx`** — extrait de `LinePicker` : compteur de trains, « tout
  afficher », « N lignes perturbées ▸ », alerte de gel. Ligne de tête de la carte sur desktop,
  **résumé toujours visible dans la poignée** sur mobile. Sans cette extraction, la feuille et
  son contenu afficheraient deux fois le compteur de trains.
- **`ui/sheetCrans.ts`** — arithmétique pure des crans, sans React (cf. section 9).
- **`ui/useIsNarrow.ts`** — le seuil, en un seul endroit côté JS.
- **`map/attribution.ts`** — le texte de la mention de source, extrait et nommé.

## 7. Mécanique de la feuille

En mode étroit, la feuille est **toujours montée**, même avant l'arrivée du réseau : son aperçu
porte le résumé, qui affiche alors « 0 train en circulation » pendant que le bandeau explique que
le plan se prépare. C'est le comportement du desktop, inchangé.

- **Trois crans fixes** : `aperçu` (poignée de 28 px + ligne de résumé de ~56 px, soit ≈ 96 px
  hors zone sûre) · `moitié` (50 dvh) · `plein` (90 dvh). Unités `dvh` et non `vh`.
  **Cran initial au chargement : `aperçu`.**
  *Conséquence assumée* : sur un contenu court (une fiche station tient dans ~200 px), le cran
  `moitié` laisse du blanc sous le dernier passage. C'est le prix d'un repère stable, qui se
  retrouve à l'aveugle — convention des applications de cartographie.
- **On ne glisse que par la poignée**, jamais par le corps du contenu. Supprime d'un trait le
  conflit entre le glissement de la feuille et le défilement interne : pas d'arbitrage à écrire,
  pas de cas limite.
- **Au lâcher** : cran le plus proche, avec un **biais de vitesse** — un geste vif franchit un
  cran même court. C'est ce biais qui sépare « ça fonctionne » de « c'est agréable ».
- **Toucher la poignée** : cran suivant, retour à l'aperçu après le plein. Une seule règle. La
  poignée est un `<button aria-expanded>`, donc utilisable au clavier sans effort supplémentaire.
- **Ouverture assistée** : toucher une station alors que la feuille est repliée l'amène à
  `moitié`. Sans quoi le geste semblerait ne rien produire (critère 4 de la section 1). Si la
  feuille est déjà plus haute, sa hauteur est conservée.
- `overscroll-behavior: none` sur `html`/`body` : sinon tirer la feuille vers le bas déclenche le
  rechargement-par-traction de Chrome Android. `touch-action: none` sur la poignée.
- `viewport-fit=cover` dans `index.html` et `env(safe-area-inset-bottom)` en marge basse, sans
  quoi la feuille passe sous la barre d'accueil des iPhone.
- Transition de hauteur de 220 ms, **désactivée pendant le glissement** (sinon la feuille traîne
  derrière le doigt).

## 8. Attribution : replier **et remonter** sous le seuil, et amender la règle

`compact: true` en mode étroit — la mention passe derrière un `ⓘ` touché en un geste — **et le
contrôle passe en `top-right`**.

Le second point n'est pas cosmétique : replier seul ne suffirait pas. Le `ⓘ` reste ancré en bas à
droite, donc **sous la feuille**, y compris repliée à 96 px — la mention deviendrait
inatteignable, ce qui est pire que dépliée. Remonté en haut à droite, il ne masque rien (un seul
bouton de 24 px, et les fiches descendent désormais dans la feuille) et reste à un geste.

Ce n'est pas l'option « déplacer en haut à droite » écartée pendant le cadrage : celle-là gardait
la mention **dépliée**, et trois à quatre lignes de texte couvraient le haut de la carte en
permanence.

C'est le cas que les recommandations OSM tolèrent explicitement, et que le commentaire de
[MapView.tsx](../../../frontend/src/map/MapView.tsx) énonce déjà lui-même (« que sur écran
contraint »). Rien n'est retiré : l'article 5.4 de la Licence Mobilité exige une mention
informant l'utilisateur, pas une mention dépliée en permanence.

**CLAUDE.md doit être amendé** dans le même commit : sa règle actuelle (« ne pas les retirer ni
les replier ») deviendrait fausse, donc ignorée. Elle dira désormais *pourquoi* l'exception
existe et où elle s'arrête — une règle qu'on comprend se respecte, une règle qu'on contredit se
perd.

Le contrôle est retiré puis reposé si le seuil est franchi en cours de session : `compact` se
fixe à la construction du contrôle, pas dynamiquement.

## 9. Vérification

Le front n'a **aucun test** : `npm run build` (typage) est le seul garde-fou automatique, et la
convention TDD du projet y est aujourd'hui inapplicable.

**Vitest est ajouté dans ce chantier** (décision prise le 2026-07-31, avance sur
[QUA-3](../../roadmap.md)), au service de l'arithmétique des crans, isolée dans
`ui/sheetCrans.ts` sans React :

- `cranHeight(cran, viewportHeight)` → hauteur en pixels, bornée.
- `snap(currentPx, velocityPxPerMs, viewportHeight)` → cran retenu au lâcher.
- `nextCran(cran)` → cran suivant, cyclique.
- `mapPadding(cran, viewportHeight)` → padding caméra, plafonné à 45 %.

Ces quatre fonctions sont écrites en TDD. Le reste (composants, gestes) est vérifié à la main —
introduire `@testing-library/react` et tester un geste de pointeur dépasse ce chantier.

**Vérification manuelle** : 390 × 844 (iPhone 12/13), 360 × 640 (petit Android), 844 × 390
(paysage, doit garder les cartes flottantes), 768 px (tablette portrait, cartes flottantes), et
un desktop large où **rien ne doit avoir bougé**.

## 10. Corrigés au passage

Ce sont les mêmes défauts, il serait artificiel de les séparer :

- `StopPanel` : `maxHeight: 70vh` → `70dvh`.
- `NetworkStatus` : `maxWidth: 360` seul débordait sous 384 px → `left`/`right: 12` + `margin: auto`.
- `html`/`body` : marges nulles et `overscroll-behavior` (premier CSS du projet).

## 11. Hors périmètre, volontairement

- **Rotation à deux doigts** laissée active : la boussole de la `NavigationControl` la rattrape.
- **Taille des cibles des flèches de train** sur la carte : toucher une flèche de 15 px reste
  difficile. Demande de retoucher `VehicleLayer`, chantier distinct.
- **Thème sombre et accès clavier complet** : c'est [UX-4](../../roadmap.md).
- **Recherche de station**, qui rendrait le tactile bien plus utile : c'est
  [UX-5](../../roadmap.md).
- **Desktop** : inchangé au pixel. La refonte de la section 6 est un déplacement de code, pas un
  changement de rendu.
