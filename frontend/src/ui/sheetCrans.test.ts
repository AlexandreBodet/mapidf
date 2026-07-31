import { describe, expect, it } from "vitest";
import { cranHeight, mapPadding, nextCran, PEEK_HEIGHT, snap } from "./sheetCrans";

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
    expect(snap(400, 0, IPHONE, "apercu")).toBe("moitie");
    expect(snap(120, 0, IPHONE, "moitie")).toBe("apercu");
  });

  it("suit un geste vif même si la feuille a peu bougé", () => {
    // Vitesse positive = la feuille grandit (doigt vers le haut).
    expect(snap(110, 1, IPHONE, "apercu")).toBe("moitie");
    expect(snap(420, -1, IPHONE, "moitie")).toBe("apercu");
  });

  it("ne boucle pas sur un geste vif : le plein reste le plein", () => {
    // nextCran est cyclique pour le toucher ; un glissement vers le haut, non.
    expect(snap(760, 1, IPHONE, "plein")).toBe("plein");
    expect(snap(96, -1, IPHONE, "apercu")).toBe("apercu");
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
