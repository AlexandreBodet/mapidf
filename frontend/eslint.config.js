// Configuration en deux étages (QUA-5 puis QUA-12).
//
// Les règles **typées** (`recommendedTypeChecked`) ne s'appliquent qu'à `src/**` : elles exigent
// que le fichier appartienne au programme TypeScript, et `tsconfig.json` n'inclut que `src`.
// `vite.config.ts` et `eslint.config.js` en sont donc exclus explicitement — sans ça, ESLint
// échoue sur « file not found in project » au lieu de les linter sans types.
//
// Ce qu'elles apportent ici, mesuré : c'est le seul outil qui ait vu que `GeoJSONSource.setData`
// est passé de `this` à `Promise<void>` en MapLibre 6. Ni `tsc` (on ignore la valeur de retour),
// ni les tests (aucun ne monte MapLibre), ni la recette navigateur (le tir-et-oublie fonctionne).
import js from "@eslint/js";
import tseslint from "typescript-eslint";
import reactHooks from "eslint-plugin-react-hooks";

export default tseslint.config(
  { ignores: ["dist/", "node_modules/"] },
  js.configs.recommended,
  ...tseslint.configs.recommended,
  {
    files: ["src/**/*.{ts,tsx}"],
    extends: [...tseslint.configs.recommendedTypeChecked],
    languageOptions: {
      parserOptions: { projectService: true, tsconfigRootDir: import.meta.dirname },
    },
  },
  { files: ["*.ts", "*.js"], ...tseslint.configs.disableTypeChecked },
  {
    files: ["**/*.{ts,tsx}"],
    plugins: { "react-hooks": reactHooks },
    rules: {
      ...reactHooks.configs.recommended.rules,
      // react-hooks v7 interdit d'écrire `ref.current` pendant le rendu ; c'est pourtant le seul
      // moyen pour un effet abonné une fois (deps `[map]` ou `[]`) de lire la dernière valeur sans
      // se ré-abonner à chaque rendu — motif déjà documenté au site de chaque usage (App.tsx,
      // useNetwork.ts, useVehicles.ts). Premier passage : 6 signalements identiques sur ce seul
      // motif intentionnel, donc la règle plutôt qu'une réécriture de six effets.
      "react-hooks/refs": "off",
    },
  },
);
