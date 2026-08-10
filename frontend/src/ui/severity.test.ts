import { describe, expect, it } from "vitest";
import { severityStyle } from "./severity";

describe("severityStyle", () => {
  it("donne une couleur ET un glyphe à chaque gravité", () => {
    // Règle d'accessibilité du projet : jamais d'information portée par la seule couleur —
    // 13/3bis et 6/7bis partagent déjà leur teinte sur la carte.
    for (const severity of ["BLOQUANTE", "PERTURBEE", "INFORMATION", "INCONNUE"] as const) {
      const style = severityStyle(severity);
      expect(style.color).toMatch(/^#[0-9a-f]{6}$/);
      expect(style.glyph).not.toBe("");
      expect(style.label).not.toBe("");
    }
  });

  it("distingue les gravités entre elles", () => {
    expect(severityStyle("BLOQUANTE").glyph).not.toBe(severityStyle("PERTURBEE").glyph);
    expect(severityStyle("BLOQUANTE").color).not.toBe(severityStyle("PERTURBEE").color);
  });

  it("retombe sur INCONNUE pour une gravité que le flux aurait inventée", () => {
    expect(severityStyle("INEDITE" as never)).toEqual(severityStyle("INCONNUE"));
  });
});
