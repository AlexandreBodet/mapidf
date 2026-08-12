import type { Severity } from "../api/types";

/**
 * Glyphe ET libellé par gravité. Les deux, pas seulement l'un : deux lignes de métro partagent
 * déjà la même teinte (13/3bis, 6/7bis), et une information portée par la seule couleur est
 * illisible pour qui ne la distingue pas. La couleur, elle, vit dans `index.css` depuis QUA-8 :
 * `[data-severity]` la descend par la variable `--sev`.
 */
const STYLES: Record<Severity, { glyph: string; label: string }> = {
  BLOQUANTE: { glyph: "✕", label: "trafic bloqué" },
  PERTURBEE: { glyph: "!", label: "trafic perturbé" },
  INFORMATION: { glyph: "i", label: "information" },
  INCONNUE: { glyph: "?", label: "perturbation" },
};

export function severityMeta(severity: Severity) {
  return STYLES[severity] ?? STYLES.INCONNUE;
}
