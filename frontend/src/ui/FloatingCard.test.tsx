// @vitest-environment jsdom
import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { FloatingCard } from "./FloatingCard";
import { expectNoA11yViolations } from "../test/axe";

afterEach(cleanup);

describe("FloatingCard", () => {
  it("est une région nommée, comme la feuille du rendu étroit", () => {
    // Un `div` anonyme n'apparaît pas dans le plan d'un lecteur d'écran.
    render(<FloatingCard anchor="bottom-left" label="État du réseau">contenu</FloatingCard>);

    expect(screen.getByRole("region", { name: "État du réseau" })).not.toBeNull();
  });

  it("garde la classe du parent en plus de la sienne", () => {
    // Régression possible du passage de `div` à `section` : la composition de classes.
    const { container } = render(
      <FloatingCard anchor="top-right" label="Détail" className="ficheStation">x</FloatingCard>,
    );

    expect(container.querySelector("section")!.className).toContain("ficheStation");
  });

  it("ne présente aucune violation détectable par axe", async () => {
    render(<FloatingCard anchor="top-right" label="Détail">contenu</FloatingCard>);

    await expectNoA11yViolations();
  });
});
