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
});
