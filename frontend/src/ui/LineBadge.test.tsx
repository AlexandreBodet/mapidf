// @vitest-environment jsdom
import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { LineBadge } from "./LineBadge";
import { expectNoA11yViolations } from "../test/axe";

afterEach(cleanup);

describe("LineBadge", () => {
  it("pose l'avant-plan lisible avec la teinte, pas seulement la teinte", () => {
    // Sur le jaune de la ligne 9, le blanc tombait à 1,62:1.
    render(<LineBadge color="#D2D200" shortName="9" size="s" />);

    const badge = screen.getByText("9");
    expect(badge.style.getPropertyValue("--line-color")).toBe("#D2D200");
    expect(badge.style.getPropertyValue("--line-fg")).toBe("#111111");
  });

  it("garde le blanc sur une teinte sombre", () => {
    render(<LineBadge color="#640082" shortName="14" size="m" />);

    expect(screen.getByText("14").style.getPropertyValue("--line-fg")).toBe("#ffffff");
  });

  it("ne présente aucune violation détectable par axe", async () => {
    render(<LineBadge color="#D2D200" shortName="9" size="s" />);

    await expectNoA11yViolations();
  });
});
