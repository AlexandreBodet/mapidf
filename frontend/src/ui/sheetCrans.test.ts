import { describe, expect, it } from "vitest";
import { clampPadding, cranHeight, mapPadding, nextCran, PEEK_HEIGHT, snap } from "./sheetCrans";

// 844 = hauteur d'un iPhone 12/13 en portrait, la cible principale du chantier.
const IPHONE = 844;

describe("cranHeight", () => {
  it("réduit l'aperçu à la poignée et au résumé", () => {
    expect(cranHeight("apercu", IPHONE)).toBe(PEEK_HEIGHT);
  });

  it("donne la moitié et 90 % de la hauteur aux deux autres crans", () => {
    expect(cranHeight("moitie", IPHONE)).toBe(422);
    expect(cranHeight("plein", IPHONE)).toBe(760);
  });

  it("ne descend jamais sous l'aperçu, même sur un écran très court", () => {
    // Sur 150 px de haut, 50 % vaudrait 75 px : la feuille rétrécirait en s'ouvrant.
    expect(cranHeight("moitie", 150)).toBe(PEEK_HEIGHT);
  });
});

describe("nextCran", () => {
  it("avance d'un cran et revient à l'aperçu après le plein", () => {
    expect(nextCran("apercu")).toBe("moitie");
    expect(nextCran("moitie")).toBe("plein");
    expect(nextCran("plein")).toBe("apercu");
  });
});

describe("snap", () => {
  it("retient le cran le plus proche quand le geste est lent", () => {
    expect(snap(400, 0, IPHONE)).toBe("moitie");
    expect(snap(120, 0, IPHONE)).toBe("apercu");
  });

  it("suit un geste vif même si la feuille a peu bougé", () => {
    // Vitesse positive = la feuille grandit (doigt vers le haut).
    expect(snap(110, 1, IPHONE)).toBe("moitie");
    expect(snap(420, -1, IPHONE)).toBe("apercu");
  });

  it("ne boucle pas sur un geste vif : le plein reste le plein", () => {
    // nextCran est cyclique pour le toucher ; un glissement vers le haut, non.
    expect(snap(760, 1, IPHONE)).toBe("plein");
    expect(snap(96, -1, IPHONE)).toBe("apercu");
  });

  it("laisse un geste vif et long franchir deux crans", () => {
    // Le biais s'ajoute au cran le plus proche : parti de l'aperçu, ce geste finit au plein.
    expect(snap(760, 2, IPHONE)).toBe("plein");
  });

  it("ne déclenche pas le biais à la limite exacte du seuil", () => {
    expect(snap(400, 0.5, IPHONE)).toBe("moitie");
  });

  it("applique la même limite stricte vers le bas", () => {
    expect(snap(400, -0.5, IPHONE)).toBe("moitie");
  });
});

describe("mapPadding", () => {
  it("suit la hauteur de la feuille tant qu'elle reste modeste", () => {
    expect(mapPadding("apercu", IPHONE)).toBe(PEEK_HEIGHT);
  });

  it("plafonne à 45 % : au-delà la géométrie de caméra devient absurde", () => {
    expect(mapPadding("plein", IPHONE)).toBe(380);
  });
});

describe("clampPadding", () => {
  it("laisse passer une hauteur mesurée modeste", () => {
    expect(clampPadding(140, IPHONE)).toBe(140);
  });

  it("plafonne une hauteur mesurée trop grande à 45 %", () => {
    expect(clampPadding(600, IPHONE)).toBe(380);
  });
});
