// `defineConfig` vient de vitest/config, sans quoi `tsc -b` rejette la clé `test` inconnue.
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
        output: { manualChunks: { maplibre: ["maplibre-gl"] } },
      },
    },
  };
});
