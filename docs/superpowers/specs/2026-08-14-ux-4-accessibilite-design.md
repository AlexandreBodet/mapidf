# UX-4 — Accessibilité : conception

Chantier UX-4 de la [feuille de route](../../roadmap.md), débloqué par QUA-8 (modules CSS, tokens
de rôle, mapping `[data-severity]`). Conception arrêtée le **2026-08-14**.

Toutes les valeurs de contraste de ce document sont **calculées** (formule WCAG 2.x de luminance
relative), pas estimées. Le seuil retenu est **4,5:1** pour le texte et **3:1** pour le non-textuel
(bordures, anneau de focus) — les textes de l'interface font 10 à 14 px, donc aucun ne relève du
seuil « grand texte » de 3:1.

## 1. Ce que la fiche disait, et ce que le code dit

La fiche UX-4 annonce quatre trous : « aucun accès clavier », « panneaux en `div` sans rôles ni
gestion du focus », « information portée par la seule couleur (13/3bis et 6/7bis identiques) »,
« aucun thème sombre ». L'audit du code en corrige trois sur quatre.

**Déjà en place, contrairement à ce que « sans rôles » laisse croire** : `aria-expanded` sur les
quatre bascules (crans de la feuille, chevron de repli, liste des perturbations, détail d'une
perturbation), `aria-pressed` sur le suivi d'un train, `aria-label` sur la poignée, la fermeture,
l'isolement de ligne et le chevron, `role="status"` sur les deux bandeaux, `aria-hidden` sur les
éléments décoratifs (séparateur `·`, heure de la poignée), et le `hidden` de `Sheet` qui sort
délibérément les 16 pastilles de l'ordre de tabulation au cran `apercu`. `Sheet` est un
`<section aria-label>` depuis UX-2 ; c'est `FloatingCard` qui est un `div` nu.

**« Information portée par la seule couleur » désigne le mauvais endroit.** Dans les panneaux,
`LineBadge` porte l'indice de ligne **en texte** : 13 et 3bis ne se confondent pas, malgré leur
teinte commune. `severity.ts` porte déjà **glyphe et libellé** en plus de la couleur, et dit
pourquoi en commentaire. Le trou couleur est sur la **carte** (tracés et flèches), où aucune
technologie d'assistance ne va de toute façon : il reste une limitation assumée, hors de ce
chantier.

**« Aucun accès clavier » est trop large.** MapLibre pose `tabindex="0"` sur son canevas quand la
carte est interactive (vérifié dans `maplibre-gl-dev.mjs`) : le déplacement et le zoom **sont**
pilotables au clavier. Ce qui manque, c'est d'atteindre une **entité** — un train, une station —
et cela ne s'obtient pas en posant des attributs : un canevas n'a pas d'enfants focusables.

**Absent sans exception, et la fiche ne le dit pas** : `:focus-visible`, `:hover` et
`prefers-color-scheme` ont **zéro occurrence** dans les douze modules CSS et dans `index.css`.
Aucun `tabIndex` ni `onKeyDown` nulle part. Aucune couleur de texte principale n'est définie : tout
hérite du défaut du navigateur.

## 2. Le défaut le plus lourd, que personne n'avait vu

`LineBadge` peint son texte en `color: var(--surface)`, soit du blanc, sur la teinte officielle de
la ligne. **Six teintes réelles sur huit échouent le seuil de 4,5:1**, jusqu'à 1,62:1 :

| ligne | teinte | blanc | noir `#111` |
|---|---|---|---|
| 9 | `#D2D200` | **1,62:1** | 11,65:1 |
| 6 / 7bis | `#82DC73` | **1,69:1** | 11,18:1 |
| 13 / 3bis | `#82C8E6` | **1,85:1** | 10,21:1 |
| 8 | `#CEADD2` | **1,99:1** | 9,47:1 |
| 7 | `#FF82B4` | **2,31:1** | 8,19:1 |
| 3 | `#6E6E00` | 5,39:1 | 3,50:1 |
| 14 | `#640082` | 11,26:1 | 1,68:1 |

L'échantillon vient des fixtures (`StationDepartureServiceTest`, `PositionEngineTest`,
`NetworkControllerIT`, `LinePicker.test.tsx`) et de CLAUDE.md : ce sont de vraies valeurs du flux
IDFM, mais **pas les seize**. La palette complète se relève sur `/network` d'une pile lancée — c'est
un point de recette (§ 10), pas un préalable à l'implémentation, la règle ne dépendant pas de la
liste.

L'hypothèse « les teintes officielles sont dessinées pour du blanc » est donc **fausse pour la
moitié claire de la palette**. La signalétique RATP fait l'inverse de nous : le 9 est noir sur
jaune.

`LineBadge` est l'élément le plus répété de l'interface — 16 pastilles du sélecteur, la tête de
chaque rangée de perturbation, la tête de chaque ligne d'une fiche station. C'est, mesuré, le
premier défaut d'accessibilité de l'application.

**Correctif** : un voisin de `lightenForTrack` dans `color.ts` qui choisit l'avant-plan de la
pastille. La règle n'est **pas** un seuil de luminance mais un choix mesuré — la fonction calcule le
contraste du blanc et celui du quasi-noir sur la teinte reçue, et retient le meilleur des deux. Un
seuil arbitraire se tromperait sur les teintes moyennes (la 3, `#6E6E00`, est le cas limite : 5,39:1
en blanc contre 3,50:1 en noir, donc blanc, alors que sa luminance est basse). Fonction pure, donc
testable en Node (là où vivent les tests rapides du projet), et **juste dans les deux thèmes** :
elle ne dépend que de la couleur de ligne, jamais de la surface.

Mesuré aussi, et à ne pas « corriger » par symétrie : **la puce de gravité de `DisruptionRow` garde
le blanc à raison**. Les quatre `--sev` sont toutes sombres — `#b91c1c` 6,47:1, `#b45309` 5,02:1,
`#1d4ed8` 6,70:1, `#6b7280` 4,83:1. Deux mécanismes différents pour deux natures différentes : les
teintes de ligne sont une **donnée non bornée** venue d'un flux (d'où le calcul), les gravités sont
**quatre valeurs que nous possédons** (d'où un token).

## 3. Périmètre

**Dans le chantier** : anneau de focus visible ; noms et rôles accessibles corrects ; structure de
document ; gestion du focus (`Échap`, retour après fermeture) ; suppression de toute information
portée par la seule couleur **dans les panneaux** ; contraste des tokens et des pastilles de ligne ;
thème sombre **des panneaux** ; garde-fou `axe-core`.

**Hors du chantier, et pourquoi** — chaque point est repris en § 11.

## 4. Focus visible

Un token `--focus`, et **une règle globale** `:focus-visible` dans `index.css`. C'est le bon
endroit : `index.css` accueille déjà les deux autres concernes valables pour tout le document, la
garde `[hidden]` et le mapping `[data-severity]`. Une règle par module dupliquerait la même
déclaration douze fois, et manquerait tout élément futur.

`--focus` **doit basculer avec le thème** : aucune teinte unique ne tient les deux surfaces
(`#1d4ed8` vaut 6,70:1 sur blanc mais 2,54:1 sur une surface sombre ; `#8ab0ff` fait l'inverse,
2,16:1 et 7,90:1). Il se définit donc comme `var(--accent)`, qui bascule déjà — 6,70:1 en clair et
7,90:1 en sombre, tous deux très au-dessus du seuil non-textuel de 3:1.

**Effet de bord souhaitable, mais partiel** : la règle globale atteint sans le nommer le `ⓘ` de
l'attribution, que `--tap` n'atteint pas — CLAUDE.md documente cette limite, parce que la corriger
demanderait de viser des classes tierces. Le zoom et la boussole, en revanche, n'en profitent pas
gratuitement : `maplibre-gl.css` pose `outline: none` sur `.maplibregl-ctrl-group button` à une
spécificité que la règle globale ne peut pas battre, d'où une seconde règle, dédiée, qui les nomme.
`:focus-visible` est un sélecteur d'état et non de classe, mais cela ne dispense pas de nommer le
groupe MapLibre dès qu'il porte lui-même un `outline: none` plus spécifique.

**Deux surcharges locales**, là où la boîte par défaut ne coïncide pas avec l'affordance visible :

- `.handle` de `Sheet` — 44 px de haut, transparents, pour un grip visible de 36×4 px. L'anneau doit
  cercler le grip, pas la bande.
- `.isolate` de `StopPanel` — boîte transparente (avec `min-height: var(--tap)`) autour d'une
  pastille de 18 px. Même problème.

`.pill` de `LinePicker` semblait en demander une troisième — son `opacity: .45` atténue son propre
anneau, l'opacité s'appliquant à l'élément entier, outline comprise. Elle devient inutile : le § 5
supprime cette `opacity` pour une autre raison, et l'anneau s'en trouve réparé au passage. Aucune
surcharge à écrire ici.

## 5. La pastille de ligne : trois défauts distincts

Les 16 pastilles sont des **bascules** (afficher / masquer une ligne) et cumulent trois défauts.

**Aucun état accessible.** `data-shown` pilote le rendu ; il n'y a pas d'`aria-pressed`. Au lecteur
d'écran, une ligne masquée est identique à une ligne affichée. Correctif : `aria-pressed`.

**Nom accessible illisible.** Le bouton contient la pastille (`9`), le compteur (`12`) et parfois un
glyphe de gravité (`!`). Un `title` est fourni, mais il n'est qu'un **dernier recours** dans le
calcul du nom accessible : le contenu textuel l'emporte, donc la pastille s'annonce « 9 12 ! ».
`StopPanel.isolate` fait déjà correctement à côté (`aria-label` **et** `title`). Correctif : un
`aria-label` explicite, énonçant ligne, décompte, état et gravité.

**Le contraste s'effondre, et l'anneau avec.** `opacity: .45` sur `--surface-off` donne un texte
effectif `#868686` sur un fond effectif `#fafafa`, soit **3,49:1**. Correctif : **supprimer
l'`opacity`** et exprimer l'état masqué par des tokens explicites — fond `--surface-off`, texte
`--text-muted` (5,17:1 en clair, 5,87:1 en sombre). Un seul changement qui règle le contraste du
compteur *et* l'atténuation de l'anneau de focus.

Note de conformité, pour ne pas surcorriger : **l'atténuation n'est pas une violation du critère
1.4.1** (« utilisation de la couleur »). Une différence de luminance reste perceptible en vision
monochrome ; ce que 1.4.1 vise, ce sont les distinctions de teinte. Le défaut ici était l'absence
d'`aria-pressed` et le nom cassé, plus le contraste que l'`opacity` faisait tomber — pas
l'atténuation elle-même. Il n'y a donc **aucun marqueur de forme à ajouter** (rayure, pastille
évidée) : ce serait du travail pour un gain nul. La lisibilité du nouvel état « masqué », plus
discret sans l'`opacity`, se juge à la recette.

## 6. Structure du document

Un lecteur d'écran n'a aujourd'hui **aucun plan** : pas de `h1`, pas de `main`, un `h3` orphelin
dans `PanelHeader`, `FloatingCard` en `div` anonyme.

- Un `<h1>` **visuellement masqué** — le titre existe déjà dans `<title>`, et l'écran est occupé par
  la carte. Masqué par une classe utilitaire (décalage hors écran), **pas** par `hidden` ni
  `display: none`, qui le retireraient aussi de l'arbre d'accessibilité. La classe vit dans
  `App.module.css`, colocalisée avec son unique consommateur — la règle QUA-8 des modules
  colocalisés s'applique, `index.css` étant réservé à ce qui vaut pour tout le document (la garde
  `[hidden]`, le mapping `[data-severity]`, l'anneau de focus du § 4, qui doit atteindre les
  contrôles MapLibre). Une classe utilisée à un seul endroit n'a rien de document-wide.
- Le `<h3>` de `PanelHeader` devient `<h2>` : sous un `h1`, c'est le niveau juste.
- `FloatingCard` reçoit une prop `label` et rend un `<section aria-label>` — exactement ce que
  `Sheet` fait déjà, donc les deux mises en page se nomment de la même façon, et le composant garde
  son rôle de coquille ignorante de son contenu.

**Pas de `aria-live` sur le compteur de trains.** Il se rafraîchit toutes les 4 s : une région live
réciterait « 703 trains… 705 trains… » sans fin. Les deux `role="status"` existants sont le bon
usage — ils ne parlent que sur changement d'**état** (chargement, panne, gel), pas sur changement de
chiffre.

## 7. Clavier et gestion du focus

- **`Échap` ferme la fiche ouverte**, station comme train. Aujourd'hui la seule sortie clavier est
  de tabuler jusqu'au `✕`. L'écouteur vit sur `document`, dans `App` — et non en `onKeyDown` sur le
  panneau : au moment où l'on veut fermer, le focus est le plus souvent **sur le canevas** (la fiche
  a été ouverte par un clic carte), donc un gestionnaire React posé sur la fiche ne se déclencherait
  jamais. `App` est de toute façon le seul à détenir les deux sélections à annuler.
- **Le focus revient au canevas de la carte** à la fermeture, par `✕` comme par `Échap`. Sans ça il
  retombe sur `body` — l'élément focalisé étant démonté — et la tabulation repart du début du
  document. Le canevas est le seul point de retour honnête : la fiche a été ouverte par un clic
  carte, il n'existe pas d'élément déclencheur à qui rendre le focus. Il est focusable par
  construction (`tabindex="0"` posé par MapLibre), et le focus y rend immédiatement le déplacement
  au clavier.
- **Pas de piège de focus, donc pas de `role="dialog"`.** Les panneaux ne sont pas modaux : la carte
  reste utilisable pendant qu'une fiche est ouverte. Un `dialog` obligerait à confiner le focus et à
  rendre le reste inerte, ce qui serait faux ici.
- Le `hidden` de `Sheet` au cran `apercu` fait déjà le nécessaire côté ordre de tabulation ; la
  recette le vérifie plutôt qu'un test (aucun test ne voit une règle CSS, cf. CLAUDE.md).

## 8. Thème sombre des panneaux

La carte reste claire (§ 11). Les panneaux deviennent des **îlots sombres sur fond clair**, ce qui a
trois conséquences que « redéfinir les tokens » ne couvre pas.

**`--surface` est utilisé comme couleur de texte.** `LineBadge` fait `color: var(--surface)` et la
puce de `DisruptionRow` aussi. En sombre, ces deux avant-plans passeraient en sombre-sur-couleur. Il
faut les séparer du token de surface :

- La pastille de ligne prend l'avant-plan **calculé** du § 2 — pas un token du tout.
- La puce de gravité prend un token dédié `--on-sev`, qui **bascule** : blanc en clair, `#17181a` en
  sombre.

**Les tokens de texte ne survivent pas au basculement.** Mesuré sur une surface sombre `#1b1c1f` :
`--text-detail` `#444` tombe à 1,75:1, `--text-muted` `#666` à 2,97:1, `--accent` `#1d4ed8` à
2,54:1, `--warn` `#b45309` à 3,39:1. Ils ont tous besoin d'une valeur propre.

**Les `--sev-*` tiennent deux rôles à la fois**, et en sombre les deux se contredisent : fond de la
puce (donc ≥ 4,5:1 avec son texte) et bordure de `.pill` (donc ≥ 3:1 avec la surface). `#b91c1c` en
sombre donne 2,63:1 en bordure — insuffisant ; l'éclaircir casse le blanc de la puce. La sortie est
la combinaison ci-dessus : **`--sev-*` éclaircis en sombre** (bordure), **`--on-sev` sombre**
(texte de la puce). Les deux alias existants survivent gratuitement :
`--sev-information: var(--accent)` et `--sev-perturbee: var(--warn)` restent vrais dans les deux
thèmes.

Palette sombre, valeurs de départ (ajustables à la recette sans changer la conception) :

| token | clair | sombre | contraste en sombre |
|---|---|---|---|
| `--surface` | `#fff` | `#1b1c1f` | — |
| `--surface-off` | `#f3f3f3` | `#2a2b2f` | — |
| `--text` *(nouveau)* | *(défaut UA)* → `#111` | `#e8e8ea` | 13,92:1 |
| `--text-detail` | `#444` | `#c6c8cc` | 10,17:1 |
| `--text-detail-open` | `#555` | `#bfc1c6` | ~9,5:1 |
| `--text-muted` | `#666` | `#a5a7ac` | 7,08:1 |
| `--text-faint` | `#999` → `#767676` | `#8f9196` | 5,40:1 |
| `--accent` | `#1d4ed8` | `#8ab0ff` | 7,90:1 |
| `--warn` | `#b45309` | `#f0a559` | 8,30:1 |
| `--sev-bloquante` | `#b91c1c` | `#ef8f8f` | 7,30:1 bordure / 7,61:1 texte |
| `--sev-inconnue` | `#6b7280` | `#a1a5ad` | 6,90:1 / 7,19:1 |
| `--on-sev` *(nouveau)* | `#fff` | `#17181a` | — |
| `--border`, `--border-subtle`, `--handle` | inchangés | éclaircis | non-textuel |
| `--separator` | inchangé (§ 11) | **assombri**, pas éclairci | non-textuel |
| `--shadow-card`, `--shadow-sheet` | inchangés | filet visible | voir ci-dessous |

`--text` arrive sur `body`, par héritage — exactement comme `--font` aujourd'hui. En clair, il vaut
`#111` et non « le défaut du navigateur » : sans valeur explicite, la règle sombre n'aurait rien à
surcharger.

**L'élévation ne se lit plus en sombre.** `--shadow-card` est un `rgba(0,0,0,.2)` : invisible sur du
sombre, alors que c'est le seul signe qui détache un panneau de la carte. En sombre, le bord se
porte par un filet plutôt que par une ombre.

**Ce qui ne bascule pas, par choix** : les deux puces d'état de `StopPanel` (`--amber-bg-strong` et
`--red-bg`, avec leur texte sombre) restent claires. Mesuré : 5,69:1 et 5,74:1 en interne, 13,68:1
et 11,78:1 contre le panneau sombre. Une puce claire sur panneau sombre est très lisible ; les
inverser serait du travail pour rien.

**Ce que le thème sombre n'est pas** : une bascule manuelle. Seul `prefers-color-scheme` est
consulté. Le projet n'a aucun écran de réglages, et un interrupteur voudrait dire un état persisté
plus un contrôle à placer, pour une préférence que le système exprime déjà.

**Autorisation explicite, contrairement à QUA-8.** QUA-8 était un refactor **à rendu identique** :
`index.css` porte encore la consigne « les nuances proches sont volontairement distinctes : les
fusionner changerait le rendu, ce qu'interdit QUA-8 ». UX-4 est l'inverse — changer le rendu **est**
son objet. Les valeurs peuvent donc bouger, et ce commentaire doit être mis à jour plutôt que
contourné.

## 9. Garde-fou : `axe-core` en direct

Une dépendance de développement, `axe-core`, et un helper d'une quinzaine de lignes dans
`src/test/` — dans la veine des `TestClock` et `firePointer` maison. **Pas de wrapper** :
`axe-core` 4.13.0 a *zéro* dépendance, là où `jest-axe` 11 embarquerait `chalk` 4, `lodash.merge` et
`jest-matcher-utils` 30 (un paquet Jest dans un projet Vitest) tout en **épinglant `axe-core`
4.12.1**, une mineure en retard ; `vitest-axe` est resté en 0.1.0, intouché depuis janvier 2025.

Le helper monte le conteneur rendu, appelle `axe.run` et échoue sur toute violation, en citant la
règle et le sélecteur fautif.

**Deux limites, à écrire dans le helper et non à découvrir** :

- **Les règles de niveau page doivent être désactivées** — `region`, `landmark-one-main`,
  `page-has-heading-one`, `html-has-lang` rougissent sur n'importe quel composant monté seul dans un
  `div`. Conséquence honnête : le `h1`, le `main` et les régions nommées du § 6, justement ce que ce
  chantier ajoute, **ne sont pas couverts par axe** — aucun test ne monte `App`, qui construit
  MapLibre. Ils se vérifient par assertions écrites à la main (`getByRole("heading", { level: 1 })`)
  et à la recette.
- **`color-contrast` ne s'évalue pas** : jsdom n'applique aucune feuille de style, axe classe la
  règle en *incomplete*, pas en violation. **Tout le contraste de ce document reste à la recette** —
  le garde-fou ne le voit pas, et ne doit pas laisser croire le contraire.

Ce que le garde-fou attrape en revanche, et que rien d'autre ne voyait : nom accessible manquant ou
vide, `aria-*` invalide ou orphelin, rôle incompatible avec l'élément, `aria-expanded` sur un
élément qui ne le supporte pas, imbrication de listes fautive.

L'assertion est branchée sur les **dix fichiers de test de composants existants** (`DisruptionRow`,
`LinePicker`, `NetworkStatus`, `NetworkSummary`, `PanelHeader`, `Sheet`, `SheetFooter`, `StaleWarning`,
`StopPanel`, `VehiclePanel`).

**Ce que ce garde-fou ne peut pas prouver, et qu'il ne faut donc pas lui demander.** Les défauts du
§ 5 lui échappent par construction : axe ne peut pas savoir qu'un bouton est une bascule, donc
l'absence d'`aria-pressed` ne lui apparaît pas ; et la pastille **a** un contenu textuel (« 912 »),
donc la règle `button-name` passe malgré un nom accessible inutilisable. Le § 6 lui échappe aussi
(règles de niveau page désactivées).

La conséquence est un ordre de travail, pas un renoncement : **axe est un filet de régression, pas
un détecteur de ces défauts-là**. Il se branche d'abord et son verdict réel se consigne tel quel —
vert partout est un résultat acceptable, c'est la ligne de base. Ce qui **doit rougir avant** les
correctifs des § 5 et 6, ce sont des **assertions écrites à la main** :
`toHaveAttribute("aria-pressed")`, `getByRole("button", { name: /Ligne 9, 12 trains/ })`,
`getByRole("heading", { level: 1 })`, `getByRole("region", { name: "État du réseau" })`. La leçon de
QUA-8 reste entière — un test de filet écrit sur du code déjà correct passe du premier coup et ne
prouve rien — mais elle porte sur ces assertions, pas sur axe.

## 10. Recette navigateur

Ce que seul un navigateur montre — aucun test ne voit une règle CSS, et `color-contrast` est aveugle
en jsdom.

1. **Tabulation complète dans les deux mises en page** (au-dessus et au-dessous de 720 px) : chaque
   contrôle atteignable, anneau visible sur chacun, ordre cohérent, et **rien de focusable au cran
   `apercu`** hormis la poignée, l'alerte de gel et l'en-tête de fiche.
2. **`Échap` ferme la fiche**, le focus revient au canevas, et les flèches déplacent aussitôt la
   carte.
3. **`prefers-color-scheme: dark` forcé** dans les outils de développement : les quatre tokens de
   texte, les deux puces claires, l'anneau de focus, et le bord des panneaux contre une carte restée
   claire.
4. **Contraste mesuré** à la pipette sur les tokens corrigés et sur les pastilles de ligne.
5. **La palette complète des 16 teintes**, relevée sur `/network` d'une pile lancée, passée au
   calcul du § 2 : c'est ce qui transforme l'échantillon de huit valeurs en couverture réelle.
6. **Passe au lecteur d'écran** (Orca ou NVDA) — le point coûteux, à trancher au moment de la
   recette. C'est la seule façon de constater qu'une pastille annonce son état et que les deux mises
   en page nomment leurs panneaux.

## 11. Hors périmètre, avec la raison

- **Atteindre un train ou une station sans souris.** Aucun attribut ne le donne : un canevas n'a pas
  d'enfants focusables. Il faut une liste ou une recherche, c'est-à-dire **UX-5** (recherche de
  station, permalien). À noter tout de même : les passages d'une fiche station **sont** des boutons
  focusables, donc un train est déjà atteignable au clavier dès qu'une station est ouverte — ce qui
  manque, c'est de pouvoir l'ouvrir.
- **Carte sombre.** Le style `dark` d'OpenFreeMap existe (fond `rgb(12,12,12)`, 47 couches), servi
  par le **même hôte** que `liberty` — donc rien à ajouter à la CSP. Ce n'est pas là que serait le
  coût : `map.setStyle()` vide sources et couches, et **rien ne les repose**. `useNetwork` est câblé
  sur `[map]` seul, son `draw` sort d'entrée sur `if (map.getSource("line-shapes")) return`, et
  `whenStyleReady` retire son écouteur `styledata` dès le premier succès ; `VehicleLayer` a la même
  forme. Après une bascule : plus de tracés, plus de stations, plus de trains, jusqu'au rechargement.
  Rendre tout cela réentrant obligerait à rejouer l'état qui vit dans des `setFilter` et non dans
  React — `stops-selected`, les trois filtres de perturbation, `visibleLines`, et les deux anneaux de
  `journeyRef` que CLAUDE.md interdit de remplacer par `feature-state`. S'y ajoutent sept couleurs
  écrites en dur pour un fond clair, dont `rgba(17,17,17,0.14)` (invisible sur `rgb(12,12,12)`) et le
  liseré blanc des flèches. C'est un chantier de carte à lui seul, dont le contenu réel — la
  réentrance — profiterait surtout à un futur sélecteur de fond : **nouvelle ligne de roadmap**.
- **Distinguer 13/3bis et 6/7bis sur la carte.** Limitation déjà assumée dans CLAUDE.md. Dans les
  panneaux le problème n'existe pas (§ 1).
- **Cibles de 24 px sur écran large.** `--tap` vaut 0 au-dessus de 720 px, et `.chevron` avec
  `padding: 0` fait la hauteur de son glyphe. Le critère 2.5.8 de WCAG 2.2 demande 24 px ; c'est un
  écart réel, mais qui ne bloque personne au clavier ni au lecteur d'écran, et le corriger
  déplacerait la mise en page des deux cartes flottantes. Consigné comme limitation.
- **`--separator` (`#bbb`, 1,92:1).** Le `·` entre deux horaires est `aria-hidden` et purement
  décoratif : la séparation est portée par les boîtes des boutons. Le critère 1.4.3 exempte le texte
  décoratif, et le remonter à 4,5:1 le ferait concurrencer les horaires qu'il sépare. Inchangé, avec
  cette raison écrite près du code.
- **Bascule manuelle clair/sombre.** Cf. § 8.
- **`role="dialog"` sur les fiches.** Cf. § 7 : elles ne sont pas modales.

## 12. Découpage

Sept tâches, chacune indépendamment relisible et testable. Le harnais arrive en deuxième, avant les
correctifs, pour que la ligne de base soit connue et non reconstituée après coup.

1. **Tokens et focus** — `--focus`, `--text`, `--on-sev`, `--text-faint` corrigé, la règle globale
   `:focus-visible`, les deux surcharges locales, la palette sombre, le filet d'élévation. CSS seul,
   aucun changement de comportement.
2. **Harnais `axe-core`** — dépendance, helper, branchement sur les dix fichiers de test de
   composants, verdict de base consigné tel quel (cf. § 9).
3. **Avant-plan calculé** — la fonction dans `color.ts` et ses tests de fonction pure, puis
   `LineBadge` qui la consomme.
4. **Pastilles de ligne** — `aria-pressed`, nom accessible, et l'état masqué exprimé sans `opacity`.
5. **Structure** — `h1` masqué, `PanelHeader` en `h2`, `FloatingCard` nommée, `--on-sev` sur la puce
   de gravité.
6. **Clavier et focus** — `Échap`, retour du focus au canevas. Vérifié à la recette seule : aucun
   test ne monte `App`, qui construit MapLibre (périmètre exclu par QUA-3).
7. **Documentation** — CLAUDE.md (la consigne « rendu identique » de QUA-8 à amender, la règle de
   l'avant-plan calculé, ce que le garde-fou ne voit pas, la règle de focus qui atteint les contrôles
   MapLibre là où `--tap` échoue), roadmap (UX-4 `fait`, nouvelle ligne UX-6 « carte sombre /
   réentrance après `setStyle` »), et la limitation 2.5.8.

## 13. Ce que ce chantier ne prétend pas

Aucune conformité n'est revendiquée. UX-4 corrige des défauts **mesurés** et installe un garde-fou
qui couvre ce que jsdom permet de couvrir. Il ne remplace pas un audit, et deux angles restent
entièrement hors de portée automatique : le contraste (§ 9) et tout ce qui concerne la carte
elle-même (§ 11).
