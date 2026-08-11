# QUA-8 — Sortir du style inline

*Document de conception — 2026-08-11*

Chantier [QUA-8](../../roadmap.md), premier de l'ordre recommandé depuis que
[QUA-3](2026-08-10-qua-3-outillage-front-design.md) a livré le harnais de composants et
[QUA-5](2026-08-11-qua-5-montee-dependances-design.md) le linter. Un attribut `style` ne peut
exprimer ni `:hover`, ni `:focus-visible`, ni `@media`, ni `prefers-color-scheme` : **UX-4 est
bloquée là**, et c'est la seule raison de faire ce chantier — le gain de sécurité qu'annonçait la
fiche n'existe pas (§ 2.4).

## 1. Objectif et critères de réussite

Déplacer le style des douze composants qui en portent vers des CSS Modules colocalisés, **sans
changer une seule fois le rendu**, et en laissant derrière soi des tests qui couvrent ce que la
conversion aurait pu casser.

Est réussi si, tout appliqué :

1. Aucun attribut `style` ne porte plus de **règle** de présentation. Il n'en subsiste que des
   **valeurs** — deux variables CSS, énumérées au § 4.
2. `npm test` est vert, avec **plus** de tests qu'avant (69 tests / 11 fichiers, mesuré le
   2026-08-11) : le filet du § 5 en ajoute six fichiers.
3. `npm run lint` reste muet et `npm run build` passe, `dist/assets/` contenant toujours son chunk
   MapLibre et son worker (cf. CLAUDE.md).
4. La recette du § 7 est propre sur les deux mises en page. C'est le **seul** contrôle du rendu :
   aucun test ne voit le CSS (§ 2.1).
5. Aucun changement visible. Ce chantier ne livre qu'un déplacement — le thème sombre et les états
   de focus sont le travail d'UX-4, qui devient possible.

## 2. Les quatre mesures qui décident de la conception

Toutes faites le 2026-08-11 par sondes jetables, supprimées depuis.

### 2.1 Les tests ne verront jamais le CSS

Sous Vitest, avec la configuration du projet (`test.css` absent, donc `false`), un module CSS
importé rend l'objet suivant :

```
STYLES OBJ = {} | probeThing = _probeThing_2d59be | typeof = string
INLINE = ""
```

Deux enseignements opposés. D'abord, **les `className` survivent** : Vitest fabrique un nom stable
(réglable en clair par `css.modules.classNameStrategy: "non-scoped"`), donc aucun composant ne se
retrouve sans classe en test. Ensuite, **aucune règle n'est appliquée** (`?inline` rend une chaîne
vide) : `getComputedStyle` restera aveugle à tout ce qui vient d'une classe. Ce point est vrai des
CSS Modules comme d'un CSS global — il ne dépend pas de la mécanique choisie.

Conséquence directe : le filet de tests protège le **comportement**, jamais l'apparence. Une couleur
ou un padding cassés ne se voient qu'en navigateur, d'où le § 7.

### 2.2 `composes` fonctionne, y compris au build rolldown

Le partage de déclarations entre modules ne demande donc aucun repli. En transform de test, la
classe exportée porte les deux noms (`"_showAll_95198f _linkBase_a3b172"`), et le build réel émet
bien deux règles distinctes :

```
._linkBase_652te_1{color:red;border:none}._showAll_1p8pk_1{margin:1px}
… _showAll_1p8pk_1 _linkBase_652te_1 …   (dans le JS émis)
```

Vérifié sur les deux chemins précisément parce que QUA-5 s'est fait piéger par un écart entre le
transform de Vite et le build rolldown (`manualChunks` en forme objet).

### 2.3 jsdom honore `[hidden]`, mais toute règle auteur l'écrase

```
a (hidden)                        | el.hidden = true | computed.display = none
b (hidden + style="display:flex") | el.hidden = true | computed.display = flex
c (hidden + règle .c{display:flex}) | el.hidden = true | computed.display = flex
```

L'attribut est donc utilisable **et** testable — mais la feuille de l'UA ayant une origine plus
faible, un futur `display: flex` sur une de ces zones déplierait la feuille en silence. D'où la
garde `[hidden] { display: none !important }` dans `index.css`, non négociable.

Le cas `c` apprend au passage que jsdom applique bien les règles d'une feuille injectée : activer
`test.css` rendrait `getComputedStyle` bavard. Écarté (§ 8) — on paierait la lenteur et la cascade
partielle de jsdom (les `@media`, dont notre seuil de 720 px, n'y sont pas fiables) pour un besoin
qui n'existe pas.

### 2.4 Le gain `style-src-attr 'none'` annoncé par la fiche n'existe pas

La fiche QUA-8 avance que sortir du style inline « permettrait `style-src-attr 'none'` ». C'est faux
dans les deux sens, et il faut le consigner plutôt que de le traîner.

**React n'écrit jamais l'attribut** : `react-dom` mute le CSSOM (`.style[key] =` × 12,
`style.setProperty` × 4 dans `react-dom-client.development.js`), jamais `setAttribute("style", …)`.
Or CSP ne gouverne que l'attribut littéral et `setAttribute` — un `style={{…}}` React échappe donc à
la directive, avant comme après ce chantier. **MapLibre mute aussi** (`transform` × 7, `width` × 4,
`height` × 3 dans sa dist), et lui n'est pas de notre ressort.

La bascule est donc indépendante de la conversion : c'est une vérification navigateur autonome, hors
périmètre. Reste ce que disait déjà la spec SEC-4 § 9 — l'attribut n'ouvre quelque chose qu'à qui a
déjà une injection HTML.

En revanche la CSP **ne bouge pas** : `style-src-elem 'self'` couvre déjà le CSS émis (`index.css`
en produit un depuis UX-2) et `style-src-attr 'unsafe-inline'` couvre les variables subsistantes. Ni
`security-headers.conf` ni `scripts/check-headers.sh` ne sont touchés.

## 3. Architecture retenue : CSS Modules en trois couches

Le scope porté par l'outil plutôt que par une convention de nommage : `.badge` existe déjà en
substance dans `LinePicker` et `DisruptionRow`, et la pastille de ligne dans trois composants — en
CSS global, la collision est silencieuse et purement visuelle, exactement la classe de régression
qu'UX-2 a documentée. Second gain : une classe d'un module non référencée par son `.tsx` voisin est
morte, mécaniquement.

| Couche | Fichier | Contenu |
|---|---|---|
| Global | `index.css` (existant) | reset, `--tap`, `--safe-bottom`, **+ tokens de rôle**, **+ garde `[hidden]`** |
| Partagé | `ui/shared.module.css` (nouveau) | les deux motifs réellement dupliqués, consommés par `composes` |
| Local | `X.module.css` à côté de `X.tsx` | douze fichiers |

**Tokens.** Toutes les couleurs passent en variables nommées par rôle dans `index.css` — les
dix-neuf, y compris celles à usage unique, plus les deux ombres portées, pour qu'UX-4 n'ait pas un
seul module à rouvrir. Inventaire mesuré :
`#fff` × 10 (surface), `#1d4ed8` × 7 (accent, également `INFORMATION`), `#666` × 6 (texte
secondaire), `#b45309` × 3 (alerte, également `PERTURBEE`), `#92400e` × 2, puis `#444`, `#555`,
`#999`, `#bbb`, `#ccc`, `#ddd`, `#eee`, `#f3f3f3`, `#fecaca`, `#991b1b`, `#fde68a`, `#fef3c7`, plus
`#b91c1c` et `#6b7280` venus de `severity.ts`. **Les nuances proches ne sont pas fusionnées** :
ramener `#444` et `#555` à un seul gris changerait le rendu, ce que le § 1 interdit.

`severity.ts` garde `glyph` et `label` — du contenu, et la raison d'être de la fonction (une
information portée par la seule couleur est illisible) — et perd `color`, qui devient
`data-severity` + tokens `--sev-*`.

**Polices.** `font-family` remonte sur `body`, où l'héritage la diffuse : c'est un gain propre de la
sortie de l'attribut, qui obligeait chaque bloc à répéter `sans-serif`. Les modules ne gardent que
taille et poids. Attention en relecture : le raccourci `font:` réinitialisait `font-weight` et
`line-height`, un `font-size` seul ne le fait pas — seul `PanelHeader` pose un `lineHeight`, le
reste est équivalent, mais c'est un point de recette.

**Deux consolidations** que la conversion rend nécessaires, sinon on duplique du CSS au lieu de le
sortir :

- **`LineBadge`** (nouveau) : la pastille ronde colorée existe trois fois — `LinePicker` en 16 px,
  son `leading` en 18 px, `StopPanel` en 18 px. Un composant, `data-size="s|m"`, la couleur en
  variable inline.
- **`FloatingCard`** : sa prop `style?: CSSProperties` devient `className?: string`, traduction
  littérale de son contrat actuel (« ce que ce panneau-là fait différemment »). `App` gagne
  `App.module.css` avec `.fiche` et `.reseau`, et le `ficheWidth` 280/260 devient
  `data-kind="station|train"` — ensemble fini de deux valeurs.

## 4. Ce qui reste dans l'attribut `style`

Uniquement des valeurs venant de la donnée ou d'une mesure, jamais une règle :

```
Sheet      style={{ "--sheet-height": `${peeking ? peekHeight : height}px` }}
LineBadge  style={{ "--line-color": line.color }}   // 16 couleurs GTFS, ensemble non borné
```

Tout le reste passe en attributs — `data-*`, ou l'ARIA déjà posé quand il dit la même chose :

| Aujourd'hui | Devient |
|---|---|
| `display: peeking ? "none"` (× 3 zones) | `hidden={peeking}` |
| `transition: dragged === null ? … : "none"` | `data-dragging` sur la `<section>` |
| `opacity: shown ? 1 : .45`, `background: shown ?` | `data-shown` |
| `severityStyle(…).color` (4 sites) | `data-severity` + `--sev-*` |
| `borderLeft: status === "error" ?` | `data-status` |
| `background`/`color: following ?` | `aria-pressed={following}` |
| `textDecoration: cancelled ?` | `data-cancelled` |
| `width: ficheWidth` | `data-kind` |

Le cas `aria-pressed` mérite d'être signalé : le bouton de suivi n'expose **aucun** état
d'accessibilité aujourd'hui. Le styler par cet attribut corrige le manque sans déborder sur UX-4,
puisque l'attribut est de toute façon nécessaire au style.

Gain de fond, au-delà de la syntaxe : `opacity: .45` n'était affirmable que par le style ;
`data-shown="false"` est un contrat observable, que le § 5 met sous test.

## 5. Filet de tests

**Deux temps par composant**, parce qu'un test écrit avant la conversion ne protège que s'il reste
vrai après. Un test affirmant `element.style.opacity === "0.45"` rougirait à la conversion : ce
n'est pas un filet, c'est du travail à refaire.

1. **Avant** : les invariants — textes rendus, handlers appelés avec les bons arguments, branches
   conditionnelles, rôles et libellés. Ils ne bougent jamais.
2. **Avec la conversion** : les états devenus observables (`hidden`, `data-shown`, `data-severity`,
   `aria-pressed`).

Contenu :

```
Nouveaux    LinePicker.test.tsx     onToggle(lineId), titre perturbé vs « N train(s) », glyphe
                                    de gravité, compteur, shortName sur la pastille
            VehiclePanel.test.tsx   nextStop / ETA / état, mention APPROXIMATE, recordedAt,
                                    onFollow, libellé « ◉ Suivi actif » / « ◉ Suivre »
            NetworkStatus.test.tsx  null si ready, titre et corps des trois états, role="status"
            StaleWarning.test.tsx   muet si !stale, message et role sinon
            SheetFooter.test.tsx    nature estimée toujours présente, heure IDFM ssi asOf
            PanelHeader.test.tsx    titre en <h3>, bouton « Fermer », onClose
Modifiés    Sheet.test.tsx          isHidden → el.hidden ; + asOf sur la poignée, onPointerCancel,
                                    MOVE_THRESHOLD (6 px reste un clic, 7 px glisse)
            DisruptionRow.test.tsx  badgeText et leading réellement rendus, titre raccourci en
                                    présence de leading, branche bouton (▸/▾, aria-expanded)
            StopPanel.test.tsx      onSelectLine au clic sur la pastille
```

`LinePicker` n'avait **aucun** test alors qu'il porte six blocs de style et quatre branches : c'est
le trou que la fiche voulait éviter. `FloatingCard` reste sans test propre — pure enveloppe, sans
branche, couverte par les rendus d'`App`.

**Réparation d'`isHidden`.** Le helper de `Sheet.test.tsx` ne lit que `style.display` inline : mesuré
lors de la rédaction de la fiche, masquer l'alerte par l'attribut `hidden` laisse ses douze tests
verts. Il devient un parcours d'ancêtres sur `el.hidden`, qui est la vérité directe et ne dépend
d'aucune feuille de style.

**Vérification annoncée par la fiche** : jsdom 27 fournit un `PointerEvent` global, donc
`fireEvent.pointerDown` transmet peut-être `clientY`. Attente à confirmer par la mesure — pas à
supposer : `firePointer` survit probablement quand même, car son autre raison d'être est le contrôle
de `timeStamp` (non settable par l'`init` de `fireEvent`, et `0` est ignoré par React, qui calcule
`event.timeStamp || Date.now()`). Ce qui se réduirait alors, c'est son commentaire de tête et la
ligne correspondante de CLAUDE.md.

## 6. Ordre d'exécution

Branche dédiée `qua-8-sortir-du-style-inline`, un commit par étape — la branche sert aussi à
comparer le rendu avant/après pendant la recette, puisque le filet ne couvre pas le visuel.

1. `index.css` : tokens de rôle, garde `[hidden]`, `font-family` sur `body`. Aucun composant touché.
2. `ui/shared.module.css` (bouton-lien, pastille) et les tests invariants du § 5 — verts sur le code
   actuel, avant toute conversion.
3. Conversions, du moins risqué au plus risqué, un composant par commit : `SheetFooter`,
   `StaleWarning`, `PanelHeader`, `NetworkStatus`, `FloatingCard` + `App`, `VehiclePanel`,
   `NetworkSummary`, `DisruptionRow`, extraction de `LineBadge`, `LinePicker`, `StopPanel`, puis
   **`Sheet` en dernier** : hauteur en variable CSS, trois `hidden`, `data-dragging`, et c'est le
   seul composant dont le style porte de la géométrie mesurée.
4. Vérifications, recette, mise à jour de CLAUDE.md et de la roadmap (§ 9).

## 7. Recette navigateur

Le seul contrôle du rendu (§ 2.1). Passée par le développeur sur sa pile — le projet ne démarre ni
n'arrête les apps depuis l'IA.

**Au-dessus de 720 px** : panneau réseau en bas à gauche (padding et police inchangés) ; fiche
station en haut à droite (largeur 280) et fiche train (largeur 260), défilement au-delà de 70 % de
la hauteur ; pastilles de ligne rondes, aux trois tailles ; une ligne masquée est atténuée et grisée ;
liste de perturbations dépliée — badge plein coloré par gravité, glyphe, détail dépliable borné à
140 px ; passage supprimé barré, badge « supprimé » / « retardé » ; bouton « Suivre » en négatif
quand le suivi est actif ; bandeau d'état avec son liseré (bleu au chargement, ambre en erreur) ;
mention de licence et heure du snapshot dans le pied.

**Sous 720 px** : les trois crans (`apercu`, `moitie`, `plein`) et leur transition de 220 ms ;
glissement de la poignée et depuis le corps ; heure « estimé HH:MM » à droite de la poignée ; au cran
`apercu`, seuls poignée, alerte de gel et en-tête de fiche restent visibles — résumé, corps et pied
disparaissent (c'est le `hidden` du § 2.3) ; cibles tactiles de 44 px ; `ⓘ` remonté en haut à droite.

**Sur la pile Docker** : console vide de toute violation CSP, et `scripts/check-headers.sh` toujours
vert — attendu inchangé (§ 2.4).

## 8. Hors périmètre, et pourquoi

- **Thème sombre et états de focus** : c'est UX-4, et c'est ce que ce chantier débloque. Les écrire
  ici rendrait la revue illisible — on ne pourrait plus distinguer « même rendu qu'avant » de
  « nouveau rendu ».
- **`test.css: true` sous Vitest** : rendrait `getComputedStyle` bavard (§ 2.3), au prix de la
  lenteur et d'une cascade partielle qui ne gère pas nos `@media`. À rouvrir si UX-4 en a besoin.
- **Tests de régression visuelle** : le seul outil qui couvrirait réellement l'apparence, mais c'est
  un chantier d'outillage navigateur à part entière.
- **Contrôles MapLibre sous 44 px** (limitation connue) : demande `:global()` sur des classes
  tierces. La sortie du style inline le rend possible, elle ne l'exige pas.
- **`style-src-attr 'none'`** : ne dépend pas de ce chantier (§ 2.4).
- **`App.tsx` et `MapView.tsx`** au-delà de leurs trois attributs de style.

## 9. Conséquences sur la documentation

- **CLAUDE.md** : la mention « style inline du projet » (limitation `--tap`) devient fausse ; la
  ligne sur `firePointer` se réduit si la mesure du § 5 le confirme ; ajouter la garde `[hidden]`
  et la règle « les valeurs dynamiques passent par des variables CSS, jamais par une règle inline ».
- **roadmap.md** : QUA-8 passe à *fait*, UX-4 perd son blocage, et la justification
  `style-src-attr 'none'` est corrigée là où elle apparaît (QUA-8, et le renvoi de SEC-4 § 9).
