# QUA-3 — Outillage front : harnais de composants et fonctions pures

*Document de conception — 2026-08-10*

Chantier [QUA-3](../../roadmap.md). Vitest y est entré par la porte d'UX-2, pour l'arithmétique
des crans ; il s'agit maintenant de pouvoir tester ce qui n'est pas une fonction pure — les
panneaux et les gestes — et de couvrir les fonctions pures restantes.

## 1. Objectif et critères de réussite

Que les défauts qui ont coûté des tours de recette dans les dernières séances soient désormais
détectables par `npm test`.

Est réussi si :

1. **Les trois régressions réellement livrées ces dernières séances ont chacune un test qui rougit
   si on remet le bug** : le préfixe de ligne conservé dans la fiche station (bug République), le
   « Service terminé » à la place du compteur hors service, et le format compact `3 min`.
2. **Le défaut clavier-après-glissement de la feuille a un test** : c'est celui qui a survécu à une
   revue et à une recette, le drapeau `moved` n'étant jamais remis à zéro.
3. **Les tests de fonctions pures ne paient pas le coût de jsdom** : l'environnement global reste
   Node, seuls les fichiers qui montent un composant demandent un DOM. C'est le mécanisme qui est
   exigé ; la durée totale est mesurée et rapportée, avec cinq secondes pour cible.
4. `npm run build` reste vert — l'extraction de `toggleLine` touche `App.tsx`.
5. Aucun changement de comportement visible dans l'application. Ce chantier ne livre que des tests
   et deux extractions à isopérimètre.

## 2. État des lieux mesuré

**25 tests, 4 fichiers**, tous sur des fonctions pures : `sheetCrans`, `lineOrder`,
`disruptionTitle`, `formatEta`. Aucun composant n'est monté nulle part, et **Vitest tourne sans
fichier de configuration** — donc en environnement Node, sans DOM.

La liste des « fonctions pures restantes » de la feuille de route est partiellement fausse, ce que
cette spec corrige :

| Nommée | Réalité mesurée |
|---|---|
| `statusKind` | Pure et exportée ([ui/status.ts](../../../frontend/src/ui/status.ts)) — testable en l'état |
| `severityStyle` | Pure et exportée ([ui/severity.ts](../../../frontend/src/ui/severity.ts)) — testable en l'état |
| `badgeText` | Pure mais **privée** dans `DisruptionRow.tsx` — demande une extraction |
| `color` | **N'existe pas.** C'est `line.color`, un champ de l'API. La feuille de route nommait une fonction fantôme |
| `toggleLine` | **Pas une fonction pure** : fermeture inline dans [App.tsx:92](../../../frontend/src/App.tsx#L92) sur `network` et `setVisibleLines` |
| culling de `VehicleLayer` | Dans une classe de 516 lignes couplée à `map.getBounds()` et à l'état des tuiles |

`toggleLine` est pourtant le meilleur candidat du lot : **quatre règles produit, zéro test.**

## 3. L'outillage

**Dépendances** (toutes en `devDependencies`) :

| Paquet | Version | Pourquoi cette version |
|---|---|---|
| `jsdom` | `^26` | Contemporain de Vitest 2.1.9 ; jsdom 30 est trois majeures au-delà de cette génération, et QUA-5 déplacera l'outillage entier |
| `@testing-library/react` | `^16` | Pair `react@^18` (mesuré) — la version du projet |
| `@testing-library/dom` | `^10` | Depuis la v16, `@testing-library/react` en fait un **pair explicite**, plus une dépendance transitive : l'omettre casse l'installation |
| `@testing-library/user-event` | `^14` | Pair `@testing-library/dom >= 7.21.4` |

Ces quatre paquets grossiront la surface des futurs `npm audit`. C'est acceptable et documenté :
comme les sept vulnérabilités relevées le 2026-08-10 (cf. SEC-6), ils sont **de développement** et
ne partent jamais au navigateur — le `Dockerfile` du front ne copie que `dist/`.

**Configuration.** Un bloc `test` est ajouté à [vite.config.ts](../../../frontend/vite.config.ts),
avec pour seul contenu `setupFiles`. **L'environnement global reste Node** : chaque fichier de test
de composant déclare son besoin par un `// @vitest-environment jsdom` en tête. C'est ce qui tient le
critère de réussite n° 3 — les 25 tests existants ne paient pas jsdom.

**`src/test/setup.ts`** porte les trois stubs que jsdom impose, chacun accompagné du fait qui l'a
rendu nécessaire, parce qu'un stub sans motif se supprime :

1. Une classe `ResizeObserver` : **absente de jsdom**, et `Sheet` en installe un en permanence pour
   mesurer son aperçu. Sans le stub, le composant lève au montage.
2. `setPointerCapture` et `releasePointerCapture` en no-op sur `Element.prototype` : jsdom **lève**
   dessus, ce qui ferait échouer le premier geste de chaque test.
3. Un utilitaire exporté, `stubHeight(element, px)`, qui remplace la `getBoundingClientRect` d'un
   élément pour qu'elle renvoie la hauteur voulue : jsdom renvoie **0** pour toute mesure, et
   `Sheet` détermine ainsi la hauteur de son aperçu. Un stub par élément, et non sur le prototype :
   un test qui impose une hauteur doit dire de quel élément il parle.

## 4. Les fonctions pures : trois tests, deux extractions

**Testées en l'état :**

- **`statusKind` / `statusLabel`** — la table de correspondance, et surtout le repli : une valeur
  inédite de PRIM doit tomber sur `unknown` plutôt que de s'afficher brute. C'est une règle de
  loyauté d'affichage, pas un détail.
- **`severityStyle`** — couleur **et** glyphe par gravité, plus le repli `INCONNUE`. Le test rend
  vérifiable la règle d'accessibilité du projet : jamais d'information portée par la seule couleur
  (13/3bis et 6/7bis partagent déjà leur teinte).

**Extraites :**

- **`badgeText`** quitte `DisruptionRow.tsx` et rejoint `disruptionTitle` dans un module renommé
  **`ui/disruptionText.ts`**. Une seule responsabilité — « le texte d'une ligne de perturbation » —
  plutôt que deux fichiers de dix lignes. Le renommage touche quatre choses : le module
  (`disruptionTitle.ts` → `disruptionText.ts`), son fichier de test (`disruptionTitle.test.ts` →
  `disruptionText.test.ts`, dont les cas existants sont conservés tels quels), l'import de
  `DisruptionRow.tsx`, et la suppression de `badgeText` de ce dernier.
- **`toggleLine`** quitte `App.tsx` pour **`ui/toggleLine.ts`** :

  ```ts
  toggleLine(current: Set<string> | null, lineId: string, lineCount: number): Set<string> | null
  ```

  Le troisième paramètre est un **nombre**, pas une liste : le code d'origine construit un `Set` de
  tous les identifiants pour n'en comparer que la taille. Comportement identique, y compris quand
  le réseau n'est pas encore chargé (`lineCount = 0`, comme le `Set` vide d'aujourd'hui).

  Ses quatre règles deviennent quatre tests :
  1. Depuis « toutes » (`null`), un clic **isole** la ligne cliquée au lieu de la retirer — sur 16
     lignes, retirer demanderait 15 clics.
  2. Un clic sur la dernière ligne visible est un **no-op**, et renvoie `current` **par identité** :
     un `Set` neuf déclencherait un re-render et le refiltrage des 321 stations pour rien. Le test
     doit vérifier l'identité (`toBe`), pas l'égalité.
  3. Un clic sur une ligne présente parmi plusieurs la retire.
  4. Un clic qui complète l'ensemble revient à `null` (« toutes »).

## 5. Le harnais : ce qu'il teste

**Les panneaux** — props vers DOM, aucun stub nécessaire.

Trois tests portent nommément les régressions livrées ces dernières séances :

- `StopPanel` affiche le **préfixe de ligne** d'une perturbation de quai (« Métro 8 : … ») : c'est
  le bug République, où la fiche d'une correspondance à cinq lignes ne disait pas laquelle.
- `NetworkSummary` affiche **« Service terminé »** et non un compteur quand `inService` est faux.
- `StopPanel` affiche **`3 min`** et non `dans 3 min` : le format compact qui fait tenir trois
  horaires sur une ligne.

Quatre autres couvrent des règles déjà présentes mais non gardées : un passage déjà parti est
filtré ; une ligne dont toutes les directions se vident disparaît ; un passage supprimé est barré et
porte son badge ; un clic sur un horaire appelle `onSelectTrain` avec le bon `journeyRef`.

**Les gestes de la feuille** — le seul volet qui aurait attrapé les défauts d'UX-2.

1. Tirer la poignée vers le haut change de cran.
2. **L'activation clavier fonctionne encore après un glissement.** C'est le défaut exact qui a
   survécu à une revue et à une recette : le drapeau `moved` n'était jamais remis à zéro, et la
   poignée devenait inerte au clavier après le premier glissement. La correction repose sur
   `event.detail === 0` ; ce test la garde.
3. **La vitesse d'un coup sec est testable, et testée** — un coup sec vers le haut ou vers le bas
   atterrit un cran plus loin que le cran le plus proche, et un dernier mouvement suivi d'une pause
   de plus de 60 ms voit sa vitesse neutralisée. `fireEvent.pointerDown/Move/Up` ne suffit pas :
   jsdom 26 n'a pas de `PointerEvent` global (repli sur `Event` nu, `clientY`/`pointerId` perdus),
   et un `timeStamp` construit à la main doit rester non nul (React calcule
   `event.timeStamp || Date.now()`). Un `MouseEvent` construit à la main, avec `pointerId` posé en
   propriété brute et un `timeStamp` maîtrisé, lève les deux limites — c'est ce que fait
   `Sheet.test.tsx`. Sans ce test, le signe de la vitesse (`applyMove`/`endDrag` jusqu'à `snap`)
   n'est couvert nulle part : `sheetCrans.test.ts` ne teste que `snap` isolément, jamais son
   acheminement depuis le geste réel.
4. **Le clic natif que le navigateur envoie après tout geste pointeur est simulé**, pas seulement
   le clic clavier (`detail: 0`) du point 2 : un tap immobile suivi de ce clic doit changer de
   cran, et le même clic après un glissement réel ne doit pas en ajouter un second.
5. Un glissement vers le bas depuis le **corps** ne replie la feuille que si ce corps est déjà
   remonté en haut (`scrollTop <= 0`) — sinon le défilement l'emporte ; et là aussi, un coup sec
   pousse un cran plus loin qu'un glissement lent de même amplitude.

## 6. Hors périmètre, et pourquoi

- **`App.tsx` et la caméra** : il faudrait un faux MapLibre complet (`easeTo`, `setPadding`,
  `getPadding`, événements) pour un défaut désormais gardé dans le code par un test explicite de
  `getPadding().bottom`. Gros périmètre de simulation, gain marginal.
- **Le culling de `VehicleLayer`** : 516 lignes couplées à l'état des tuiles. À extraire un jour,
  pas ici.
- **ESLint** : utile, mais il n'aurait attrapé **aucun** des défauts d'UX-2 — ni l'hypothèse fausse
  d'un commentaire, ni un geste, ni un padding de caméra. Il part avec **QUA-5**, dont les montées
  de majeure profiteront du même passage d'outillage.
- **Prettier** : repoussé à **QUA-8**, qui réécrira les styles de tous les composants. Deux
  reformatages massifs coup sur coup se marcheraient dessus, le second annulant une partie du
  premier.

## 7. Vérification

- `npm test` vert, et **chronométré** : le critère des cinq secondes se mesure, il ne se suppose
  pas.
- Pour chacune des trois régressions nommées au § 5, l'implémenteur **remet le bug**, constate que
  le test rougit, puis le retire. Un test de régression qui n'a jamais échoué ne prouve rien.
- Même exigence pour le défaut clavier : neutraliser `event.detail === 0`, voir le test rougir.
- `npm run build` vert (`tsc -b` compris) après l'extraction de `toggleLine`.
- Le comportement de l'application est inchangé : aucun test de composant ne doit avoir demandé une
  modification du composant qu'il teste. Si l'un l'exige, c'est un constat à remonter, pas une
  retouche à faire au passage.

## 8. Conséquence sur la feuille de route

QUA-3 passera à `fait` en décrivant ce qu'il couvre **et** ce qu'il ne couvre pas (App/caméra,
culling). Deux renvois sont à corriger dans le même geste : la mention de `color` disparaît, et la
ligne dira qu'ESLint est parti avec QUA-5 et Prettier avec QUA-8 — sans quoi QUA-3 restera
éternellement « entamé » aux yeux du prochain lecteur.
