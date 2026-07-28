import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig({
  plugins: [react()],
  server: { proxy: { "/api": "http://localhost:8000" } },
  build: {
    rollupOptions: {
      // MapLibre pèse l'essentiel du bundle et bouge rarement : chunk séparé →
      // mieux caché entre deux déploiements et chunk applicatif plus léger.
      output: { manualChunks: { maplibre: ["maplibre-gl"] } },
    },
  },
});
