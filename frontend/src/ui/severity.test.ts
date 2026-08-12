import { describe, expect, it } from "vitest";
import { severityMeta } from "./severity";

describe("severityMeta", () => {
  it("donne un glyphe ET un libellé à chaque gravité", () => {
    // Règle d'accessibilité du projet : jamais d'information portée par la seule couleur —
    // 13/3bis et 6/7bis partagent déjà leur teinte sur la carte. La couleur elle-même a rejoint
    // `index.css` avec QUA-8 (variable `--sev`), et n'est plus vérifiable ici.
    for (const severity of ["BLOQUANTE", "PERTURBEE", "INFORMATION", "INCONNUE"] as const) {
      const style = severityMeta(severity);
      expect(style.glyph).not.toBe("");
      expect(style.label).not.toBe("");
    }
  });

  it("distingue les gravités entre elles", () => {
    const severities = ["BLOQUANTE", "PERTURBEE", "INFORMATION", "INCONNUE"] as const;
    const styles = severities.map(severityMeta);
    expect(new Set(styles.map((s) => s.glyph))).toHaveLength(4);
    // Sans ce second axe, INFORMATION pourrait hériter du libellé de BLOQUANTE : `badgeText`
    // retombe sur ce libellé, un badge annoncerait « trafic bloqué » sur une simple information.
    expect(new Set(styles.map((s) => s.label))).toHaveLength(4);
  });

  it("retombe sur INCONNUE pour une gravité que le flux aurait inventée", () => {
    expect(severityMeta("INEDITE" as never)).toEqual(severityMeta("INCONNUE"));
  });
});
