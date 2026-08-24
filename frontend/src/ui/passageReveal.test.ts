import { describe, expect, it } from "vitest";
import { DEFAULT_REVEALED_PASSAGES, REVEAL_STEP, revealMore, revealedCountFor } from "./passageReveal";

describe("passageReveal", () => {
  it("vaut le défaut quand la direction n'a jamais été dépliée", () => {
    expect(revealedCountFor({}, "4", "Bagneux - Lucie Aubrac")).toBe(DEFAULT_REVEALED_PASSAGES);
  });

  it("incrémente d'un cran la direction cliquée sans toucher les autres", () => {
    const revealed = revealMore({}, "4", "Bagneux - Lucie Aubrac");

    expect(revealedCountFor(revealed, "4", "Bagneux - Lucie Aubrac"))
      .toBe(DEFAULT_REVEALED_PASSAGES + REVEAL_STEP);
    expect(revealedCountFor(revealed, "4", "Porte de Clignancourt")).toBe(DEFAULT_REVEALED_PASSAGES);
  });

  it("distingue deux lignes qui partagent le même nom de destination", () => {
    const revealed = revealMore({}, "7", "Châtelet");

    expect(revealedCountFor(revealed, "11", "Châtelet")).toBe(DEFAULT_REVEALED_PASSAGES);
  });

  it("cumule les clics successifs sur la même direction", () => {
    const revealed = revealMore(revealMore({}, "14", "Aéroport d'Orly"), "14", "Aéroport d'Orly");

    expect(revealedCountFor(revealed, "14", "Aéroport d'Orly")).toBe(DEFAULT_REVEALED_PASSAGES + 2 * REVEAL_STEP);
  });
});
