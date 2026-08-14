import { describe, expect, it } from "vitest";
import { lightenForTrack, readableOn } from "./color";

describe("lightenForTrack", () => {
  it("éclaircit vers le blanc en gardant 45 % de la teinte", () => {
    expect(lightenForTrack("#D2D200")).toBe("rgb(235, 235, 140)");
    expect(lightenForTrack("#640082")).toBe("rgb(185, 140, 199)");
  });

  it("accepte la forme courte à trois chiffres", () => {
    expect(lightenForTrack("#f00")).toBe("rgb(255, 140, 140)");
  });

  it("traite une composante illisible comme un zéro", () => {
    // Le tracé doit rester dessiné même si le flux sert une couleur cassée.
    expect(lightenForTrack("#zzzzzz")).toBe("rgb(140, 140, 140)");
  });
});

describe("readableOn", () => {
  it("choisit le quasi-noir sur les teintes claires du réseau", () => {
    // Le blanc y tombait entre 1,62:1 et 2,31:1 : c'est le défaut que ce chantier corrige.
    expect(readableOn("#D2D200")).toBe("#111111"); // 9
    expect(readableOn("#82DC73")).toBe("#111111"); // 6 et 7bis
    expect(readableOn("#82C8E6")).toBe("#111111"); // 13 et 3bis
    expect(readableOn("#CEADD2")).toBe("#111111"); // 8
    expect(readableOn("#FF82B4")).toBe("#111111"); // 7
  });

  it("garde le blanc sur les teintes sombres du réseau", () => {
    expect(readableOn("#640082")).toBe("#ffffff"); // 14
  });

  it("tranche par la mesure et non par un seuil de luminance", () => {
    // La ligne 3 est le cas limite : luminance basse, et pourtant le blanc contraste mieux
    // (5,39:1 contre 3,50:1). Un seuil de luminance se tromperait ici.
    expect(readableOn("#6E6E00")).toBe("#ffffff");
  });

  it("reste lisible sur les extrêmes et sur une couleur cassée", () => {
    expect(readableOn("#000000")).toBe("#ffffff");
    expect(readableOn("#ffffff")).toBe("#111111");
    // Composantes illisibles traitées comme du noir, comme dans lightenForTrack.
    expect(readableOn("#zzzzzz")).toBe("#ffffff");
  });
});
