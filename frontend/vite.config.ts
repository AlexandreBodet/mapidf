// `defineConfig` vient de vitest/config : la clé `test` n'existe que sur ce type. `tsc -b` ne
// nous alerterait pas (tsconfig.json a `include: ["src"]`, ce fichier n'est jamais compilé), mais
// un IDE la signalerait comme inconnue avec l'import `vite` — et c'est la voie documentée par Vitest.
import { defineConfig } from "vitest/config";
import { loadEnv } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig(({ mode }) => {
  // Le `.env` de la racine est la source unique des ports (cf. README) : on le relit ici pour que
  // le proxy suive le backend sans qu'un dev ait à le redire au front. Seul `SERVER_PORT` est
  // retenu — le reste du fichier (dont la clé PRIM) ne doit pas approcher la config du bundle.
  const { SERVER_PORT } = { ...loadEnv(mode, "..", ""), ...process.env };
  return {
    plugins: [react()],
    // Environnement Node par défaut : seuls les fichiers montant un composant demandent jsdom,
    // par un `// @vitest-environment jsdom` en tête de fichier.
    test: { setupFiles: ["./src/test/setup.ts"] },
    server: { proxy: { "/api": `http://localhost:${SERVER_PORT ?? "8100"}` } },
    build: {
      rollupOptions: {
        // MapLibre pèse l'essentiel du bundle et bouge rarement : chunk séparé →
        // mieux caché entre deux déploiements et chunk applicatif plus léger.
        // Vite 8 a remplacé rollup par rolldown : la forme objet de `manualChunks` (celle du
        // dessus, chère à rollup) fait échouer le build ("manualChunks is not a function") —
        // rolldown ne garde que la forme fonction, et la documente comme dépréciée au profit de
        // `output.codeSplitting.groups`, utilisé ici avec un `test` par regex plutôt qu'une
        // liste de modules.
        output: { codeSplitting: { groups: [{ name: "maplibre", test: /maplibre-gl/ }] } },
      },
    },
  };
});
