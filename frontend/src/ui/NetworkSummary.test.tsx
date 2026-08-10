// @vitest-environment jsdom
import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { NetworkSummary } from "./NetworkSummary";

// Sans `globals: true`, le nettoyage automatique de Testing Library ne s'enregistre pas : les
// rendus s'accumuleraient d'un test à l'autre et les recherches trouveraient plusieurs éléments.
afterEach(cleanup);

function renderSummary(props: Partial<Parameters<typeof NetworkSummary>[0]> = {}) {
  return render(
    <NetworkSummary
      total={12}
      inService
      disruptedCount={0}
      disruptionsOpen={false}
      onToggleDisruptions={vi.fn()}
      canShowAll={false}
      onShowAll={vi.fn()}
      {...props}
    />,
  );
}

describe("NetworkSummary", () => {
  it("annonce le service terminé au lieu d'un compteur, hors des heures de service", () => {
    // Régression livrée : la nuit, « 705 trains en circulation » alors que le flux est éteint.
    renderSummary({ inService: false, total: 705 });

    expect(screen.queryByText("Service terminé")).not.toBeNull();
    expect(screen.queryByText("Reprise au premier métro.")).not.toBeNull();
    expect(screen.queryByText(/trains en circulation/)).toBeNull();
  });

  it("compte les trains quand le service est ouvert", () => {
    renderSummary({ total: 12 });

    expect(screen.queryByText("12 trains en circulation")).not.toBeNull();
    expect(screen.queryByText("Service terminé")).toBeNull();
  });

  it("accorde le nombre de lignes perturbées", () => {
    const seule = renderSummary({ disruptedCount: 1 });
    expect(screen.queryByText(/^1 ligne perturbée/)).not.toBeNull();
    seule.unmount();

    renderSummary({ disruptedCount: 3 });
    expect(screen.queryByText(/^3 lignes perturbées/)).not.toBeNull();
  });

  it("n'offre « tout afficher » que si un sous-ensemble est isolé", () => {
    const tout = renderSummary({ canShowAll: false });
    expect(screen.queryByText("tout afficher")).toBeNull();
    tout.unmount();

    renderSummary({ canShowAll: true });
    expect(screen.queryByText("tout afficher")).not.toBeNull();
  });
});
