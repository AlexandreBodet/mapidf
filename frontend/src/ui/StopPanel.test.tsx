// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { DeparturesResponse } from "../api/types";
import { StopPanel } from "./StopPanel";

// Sans `globals: true`, le nettoyage automatique de Testing Library ne s'enregistre pas.
afterEach(cleanup);

// +5 s de marge, sans quoi les millisecondes écoulées entre la fixture et le rendu font tomber
// le `Math.floor(sec / 60)` de formatEta sur la minute inférieure : le test serait intermittent.
const inMinutes = (min: number) => new Date(Date.now() + min * 60_000 + 5_000).toISOString();

function departures(overrides: Partial<DeparturesResponse> = {}): DeparturesResponse {
  return {
    stationName: "République",
    disruptions: [],
    lines: [
      {
        lineId: "3", shortName: "3", color: "#CEADD2",
        directions: [
          {
            destination: "Gallieni",
            passages: [
              { journeyRef: "J-1", expectedTime: inMinutes(3), status: "ON_TIME" },
              { journeyRef: "J-2", expectedTime: inMinutes(7), status: "ON_TIME" },
            ],
          },
        ],
      },
    ],
    ...overrides,
  };
}

describe("StopPanel", () => {
  it("affiche les horaires au format compact, pour que trois tiennent sur une ligne", () => {
    // Régression livrée : « dans 3 min » faisait ~237 px à trois, pour 260 disponibles, donc un
    // repli imprévisible et un séparateur orphelin en début de ligne.
    render(<StopPanel data={departures()} />);

    expect(screen.queryByText("3 min")).not.toBeNull();
    expect(screen.queryByText("dans 3 min")).toBeNull();
  });

  it("dit quelle ligne n'est pas desservie", () => {
    // Régression livrée : à République, correspondance à cinq lignes, la fiche affichait
    // « Arrêt non desservi » sans jamais dire qu'il s'agissait de la 8.
    render(<StopPanel data={departures({
      disruptions: [{
        severity: "BLOQUANTE", cause: "TRAVAUX",
        title: "Métro 8 : Travaux de rénovation - Arrêt non desservi",
        shortMessage: "Arrêt non desservi", detail: "",
      }],
    })} />);

    expect(screen.queryByText("Métro 8 : Travaux de rénovation - Arrêt non desservi")).not.toBeNull();
  });

  it("masque un passage déjà parti, et la ligne qui n'a plus rien à annoncer", () => {
    render(<StopPanel data={departures({
      lines: [{
        lineId: "3", shortName: "3", color: "#CEADD2",
        directions: [{
          destination: "Gallieni",
          passages: [{ journeyRef: "J-0", expectedTime: inMinutes(-2), status: "ON_TIME" }],
        }],
      }],
    })} />);

    // Le texte normalisé est « → Gallieni » (cf. `<p>→ {dir.destination}</p>`) : un matcher exact
    // sur "Gallieni" seul ne correspond jamais, quelle que soit l'implémentation.
    expect(screen.queryByText(/Gallieni/)).toBeNull();
    expect(screen.queryByText("Aucun passage annoncé.")).not.toBeNull();
  });

  it("affiche la destination quand il reste au moins un passage à venir", () => {
    render(<StopPanel data={departures()} />);

    expect(screen.queryByText(/Gallieni/)).not.toBeNull();
  });

  it("barre un passage supprimé et le dit, au lieu d'une heure en bleu comme les autres", () => {
    render(<StopPanel data={departures({
      lines: [{
        lineId: "3", shortName: "3", color: "#CEADD2",
        directions: [{
          destination: "Gallieni",
          passages: [{ journeyRef: "J-1", expectedTime: inMinutes(4), status: "CANCELLED" }],
        }],
      }],
    })} />);

    expect(screen.queryByText("supprimé")).not.toBeNull();
    const heure = screen.getByText("4 min");
    // Ce test lisait le style inline, que QUA-8 a supprimé ; l'état est désormais porté par
    // `data-cancelled`, un contrat que le CSS lit et que le test peut affirmer sans connaître
    // la règle.
    expect(heure.getAttribute("data-cancelled")).toBe("true");
  });

  it("remonte le journeyRef du passage cliqué", () => {
    const onSelectTrain = vi.fn();
    render(<StopPanel data={departures()} onSelectTrain={onSelectTrain} />);

    fireEvent.click(screen.getByText("7 min"));

    expect(onSelectTrain).toHaveBeenCalledWith("J-2");
  });

  it("isole la ligne dont on clique la pastille", () => {
    // Isolement inconditionnel, comme un clic dans le sélecteur du bas : quel que soit le filtre
    // courant, ce clic ne laisse que cette ligne (décision produit).
    const onSelectLine = vi.fn();
    render(<StopPanel data={departures()} onSelectLine={onSelectLine} />);

    fireEvent.click(screen.getByRole("button", { name: "N'afficher que la ligne 3" }));

    expect(onSelectLine).toHaveBeenCalledExactlyOnceWith("3");
  });
});
