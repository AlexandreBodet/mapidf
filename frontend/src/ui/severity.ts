import type { Severity } from "../api/types";

/**
 * Couleur ET glyphe par gravité. Les deux, pas seulement la couleur : deux lignes de métro
 * partagent déjà la même teinte (13/3bis, 6/7bis), et une information portée par la seule
 * couleur est illisible pour qui ne la distingue pas.
 */
const STYLES: Record<Severity, { color: string; glyph: string; label: string }> = {
  BLOQUANTE: { color: "#b91c1c", glyph: "✕", label: "trafic bloqué" },
  PERTURBEE: { color: "#b45309", glyph: "!", label: "trafic perturbé" },
  INFORMATION: { color: "#1d4ed8", glyph: "i", label: "information" },
  INCONNUE: { color: "#6b7280", glyph: "?", label: "perturbation" },
};

export function severityStyle(severity: Severity) {
  return STYLES[severity] ?? STYLES.INCONNUE;
}
