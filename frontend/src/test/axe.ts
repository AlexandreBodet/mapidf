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
