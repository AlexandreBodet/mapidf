import { describe, expect, it } from "vitest";
import { badgeText, disruptionTitle } from "./disruptionText";

describe("disruptionTitle", () => {
  it("retire le préfixe de ligne quand une pastille le porte déjà", () => {
    expect(disruptionTitle("Métro 13 : Travaux - Arrêt non desservi", true))
      .toBe("Travaux - Arrêt non desservi");
  });

  it("garde le préfixe quand rien d'autre ne dit la ligne", () => {
    // Le cas du bug : à République (correspondance à 5 lignes), la fiche station n'affichait
    // que « Arrêt non desservi », sans dire que c'était la 8.
    expect(disruptionTitle("Métro 8 : Travaux de rénovation - Arrêt non desservi", false))
      .toBe("Métro 8 : Travaux de rénovation - Arrêt non desservi");
  });

  it("garde le titre entier si le format du flux diffère", () => {
    expect(disruptionTitle("Arrêt non desservi", true)).toBe("Arrêt non desservi");
  });

  it("ne coupe pas un titre qui commence par le séparateur", () => {
    expect(disruptionTitle(" : Travaux", true)).toBe(" : Travaux");
  });
});

describe("badgeText", () => {
  it("garde le résumé du flux quand il dit quelque chose", () => {
    expect(badgeText("Arrêt non desservi", "trafic bloqué")).toBe("Arrêt non desservi");
  });

  it("préfère le libellé de gravité au « Autre » du flux, qui ne dit rien", () => {
    expect(badgeText("Autre", "information")).toBe("information");
    expect(badgeText("autre", "information")).toBe("information");
  });

  it("préfère le libellé de gravité à un résumé vide", () => {
    expect(badgeText("", "trafic perturbé")).toBe("trafic perturbé");
  });
});
