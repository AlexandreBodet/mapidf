// Configuration délibérément minimale (QUA-5). Le premier passage porte sur 3 665 lignes jamais
// lintées : une règle qui produirait un flot de corrections mécaniques se désactive ici plutôt que
// de mêler un reformatage massif à une migration de dépendances. Le formatage viendra avec QUA-8.
import js from "@eslint/js";
import tseslint from "typescript-eslint";
import reactHooks from "eslint-plugin-react-hooks";

export default tseslint.config(
  { ignores: ["dist/", "node_modules/"] },
  js.configs.recommended,
  ...tseslint.configs.recommended,
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
