// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import type { DisruptionItem } from "../api/types";
import { DisruptionRow } from "./DisruptionRow";

// Sans `globals: true`, le nettoyage automatique de Testing Library ne s'enregistre pas.
afterEach(cleanup);

const item = (overrides: Partial<DisruptionItem> = {}): DisruptionItem => ({
  severity: "PERTURBEE", cause: "PERTURBATION",
  title: "Métro 5 : Incident - Train stationne",
  shortMessage: "Train stationne", detail: "",
  ...overrides,
});

describe("DisruptionRow", () => {
  it("ne rend cliquable que ce qui a un détail à révéler", () => {
    // Sinon le curseur mentirait : un bouton qui n'ouvre rien.
    const sans = render(<ul><DisruptionRow item={item()} /></ul>);
    expect(screen.queryByRole("button")).toBeNull();
    sans.unmount();

    render(<ul><DisruptionRow item={item({ detail: "Un train stationne à Bobigny." })} /></ul>);
    expect(screen.queryByRole("button")).not.toBeNull();
  });

  it("révèle le détail au clic, et le referme", () => {
    render(<ul><DisruptionRow item={item({ detail: "Un train stationne à Bobigny." })} /></ul>);

    expect(screen.queryByText("Un train stationne à Bobigny.")).toBeNull();
    fireEvent.click(screen.getByRole("button"));
    expect(screen.queryByText("Un train stationne à Bobigny.")).not.toBeNull();
    fireEvent.click(screen.getByRole("button"));
    expect(screen.queryByText("Un train stationne à Bobigny.")).toBeNull();
  });

  it("substitue le libellé de gravité au résumé quand le flux met « Autre »", () => {
    // `badgeText` a ses tests unitaires ; rien ne prouvait que le composant l'appelle. Mesuré :
    // « Métro 14 : Information - Autre » n'a de sens que par son libellé de gravité.
    render(<ul><DisruptionRow item={item({ shortMessage: "Autre", severity: "INFORMATION" })} /></ul>);

    expect(screen.queryByText("information")).not.toBeNull();
    expect(screen.queryByText("Autre")).toBeNull();
  });

  it("raccourcit le titre quand une pastille de ligne le précède, pas sinon", () => {
    // La présence de `leading` EST la condition de `disruptionTitle` : le sélecteur montre la
    // ligne dans sa pastille, la fiche station n'a que le nom de la station.
    const avecPastille = render(
      <ul><DisruptionRow item={item()} leading={<span>9</span>} /></ul>,
    );
    expect(screen.queryByText("Incident - Train stationne")).not.toBeNull();
    expect(screen.queryByText("Métro 5 : Incident - Train stationne")).toBeNull();
    expect(screen.queryByText("9")).not.toBeNull();
    avecPastille.unmount();

    render(<ul><DisruptionRow item={item()} /></ul>);
    expect(screen.queryByText("Métro 5 : Incident - Train stationne")).not.toBeNull();
  });
});
