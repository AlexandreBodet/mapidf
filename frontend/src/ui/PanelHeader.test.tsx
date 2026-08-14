// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { expectNoA11yViolations } from "../test/axe";
import { PanelHeader } from "./PanelHeader";

afterEach(cleanup);

describe("PanelHeader", () => {
  it("porte le titre en en-tête de niveau 2, sous le h1 de la page", () => {
    render(<PanelHeader title="République" onClose={vi.fn()} />);

    expect(screen.getByRole("heading", { level: 2 }).textContent).toBe("République");
  });

  it("ferme par un bouton nommé, atteignable au lecteur d'écran", () => {
    // Le « ✕ » seul ne dit rien : c'est `aria-label` qui nomme le bouton.
    const onClose = vi.fn();
    render(<PanelHeader title="République" onClose={onClose} />);

    fireEvent.click(screen.getByRole("button", { name: "Fermer" }));

    expect(onClose).toHaveBeenCalledOnce();
  });

  it("ne présente aucune violation détectable par axe", async () => {
    render(<PanelHeader title="République" onClose={vi.fn()} />);

    await expectNoA11yViolations();
  });
});
