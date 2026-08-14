// @vitest-environment jsdom
import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { expectNoA11yViolations } from "../test/axe";
import { SheetFooter } from "./SheetFooter";

// Sans `globals: true`, le nettoyage automatique de Testing Library ne s'enregistre pas.
afterEach(cleanup);

describe("SheetFooter", () => {
  it("annonce toujours la nature estimée des positions", () => {
    // Art. 5.4/5.7 de la Licence Mobilité : cette mention n'est pas cosmétique.
    render(<SheetFooter asOf={null} />);

    expect(screen.queryByText(/Position estimée \(pas de GPS en métro\)/)).not.toBeNull();
  });

  it("ne tamponne une heure que s'il y a un instantané", () => {
    // L'art. 5.7 interdit d'induire en erreur sur la date de mise à jour autant que sur le
    // contenu : avant le premier poll, il n'y a aucune heure à afficher.
    const avant = render(<SheetFooter asOf={null} />);
    expect(screen.queryByText(/Données IDFM du/)).toBeNull();
    avant.unmount();

    render(<SheetFooter asOf="2026-08-11T14:32:10Z" />);
    expect(screen.queryByText(/Données IDFM du/)).not.toBeNull();
  });

  it("ne présente aucune violation détectable par axe", async () => {
    render(<SheetFooter asOf="2026-08-11T14:32:10Z" />);

    await expectNoA11yViolations();
  });
});
