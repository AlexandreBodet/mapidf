// @vitest-environment jsdom
import { useState } from "react";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { DeparturesResponse } from "../api/types";
import { expectNoA11yViolations } from "../test/axe";
import { StopPanel } from "./StopPanel";
import { revealMore } from "./passageReveal";

// Sans `globals: true`, le nettoyage automatique de Testing Library ne s'enregistre pas.
afterEach(cleanup);

/**
 * `StopPanel` est un composant contrôlé (cf. commentaire dans StopPanel.tsx) : l'état de
 * dépliage vit dans `App`, pas ici. Ce wrapper tient l'état à la place d'App pour que les tests
 * d'interaction ("Voir plus") observent un vrai re-rendu ; les tests qui ne touchent pas au
 * dépliage passent directement `revealed={{}}` sans wrapper.
 */
function ControlledStopPanel(props: Omit<Parameters<typeof StopPanel>[0], "revealed" | "onReveal">) {
  const [revealed, setRevealed] = useState<Record<string, number>>({});
  return (
    <StopPanel
      {...props}
      revealed={revealed}
      onReveal={(lineId, destination) => setRevealed((prev) => revealMore(prev, lineId, destination))}
    />
  );
}

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
    render(<StopPanel data={departures()} revealed={{}} onReveal={() => {}} />);

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
    })} revealed={{}} onReveal={() => {}} />);

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
    })} revealed={{}} onReveal={() => {}} />);

    // Le texte normalisé est « → Gallieni » (cf. `<p>→ {dir.destination}</p>`) : un matcher exact
    // sur "Gallieni" seul ne correspond jamais, quelle que soit l'implémentation.
    expect(screen.queryByText(/Gallieni/)).toBeNull();
    expect(screen.queryByText("Aucun passage annoncé.")).not.toBeNull();
  });

  it("affiche la destination quand il reste au moins un passage à venir", () => {
    render(<StopPanel data={departures()} revealed={{}} onReveal={() => {}} />);

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
    })} revealed={{}} onReveal={() => {}} />);

    expect(screen.queryByText("supprimé")).not.toBeNull();
    const heure = screen.getByText("4 min");
    // Ce test lisait le style inline, que QUA-8 a supprimé ; l'état est désormais porté par
    // `data-cancelled`, un contrat que le CSS lit et que le test peut affirmer sans connaître
    // la règle.
    expect(heure.getAttribute("data-cancelled")).toBe("true");
  });

  it("remonte le journeyRef du passage cliqué", () => {
    const onSelectTrain = vi.fn();
    render(<StopPanel data={departures()} revealed={{}} onReveal={() => {}} onSelectTrain={onSelectTrain} />);

    fireEvent.click(screen.getByText("7 min"));

    expect(onSelectTrain).toHaveBeenCalledWith("J-2");
  });

  it("isole la ligne dont on clique la pastille", () => {
    // Isolement inconditionnel, comme un clic dans le sélecteur du bas : quel que soit le filtre
    // courant, ce clic ne laisse que cette ligne (décision produit).
    const onSelectLine = vi.fn();
    render(<StopPanel data={departures()} revealed={{}} onReveal={() => {}} onSelectLine={onSelectLine} />);

    fireEvent.click(screen.getByRole("button", { name: "N'afficher que la ligne 3" }));

    expect(onSelectLine).toHaveBeenCalledExactlyOnceWith("3");
  });

  it("plafonne l'affichage à 3 passages avec un bouton pour en révéler plus", () => {
    render(<StopPanel data={departures({
      lines: [{
        lineId: "3", shortName: "3", color: "#CEADD2",
        directions: [{
          destination: "Gallieni",
          passages: [1, 2, 3, 4, 5].map((min) => (
            { journeyRef: `J-${min}`, expectedTime: inMinutes(min), status: "ON_TIME" }
          )),
        }],
      }],
    })} revealed={{}} onReveal={() => {}} />);

    expect(screen.queryByText("1 min")).not.toBeNull();
    expect(screen.queryByText("2 min")).not.toBeNull();
    expect(screen.queryByText("3 min")).not.toBeNull();
    expect(screen.queryByText("4 min")).toBeNull();
    expect(screen.queryByText("5 min")).toBeNull();
    expect(screen.queryByRole("button", { name: "Voir plus" })).not.toBeNull();
  });

  it("révèle 3 passages de plus par clic sur \"Voir plus\", jusqu'à épuisement", () => {
    render(<ControlledStopPanel data={departures({
      lines: [{
        lineId: "3", shortName: "3", color: "#CEADD2",
        directions: [{
          destination: "Gallieni",
          passages: [1, 2, 3, 4, 5].map((min) => (
            { journeyRef: `J-${min}`, expectedTime: inMinutes(min), status: "ON_TIME" }
          )),
        }],
      }],
    })} />);

    fireEvent.click(screen.getByRole("button", { name: "Voir plus" }));

    expect(screen.queryByText("4 min")).not.toBeNull();
    expect(screen.queryByText("5 min")).not.toBeNull();
    // Il n'existe qu'un 6e passage inexistant : plus rien à révéler, le bouton disparaît.
    expect(screen.queryByRole("button", { name: "Voir plus" })).toBeNull();
  });

  it("n'affiche aucun bouton \"Voir plus\" quand tout tient déjà en 3 passages", () => {
    render(<StopPanel data={departures()} revealed={{}} onReveal={() => {}} />);

    expect(screen.queryByRole("button", { name: "Voir plus" })).toBeNull();
  });

  it("ne présente aucune violation détectable par axe", async () => {
    render(<StopPanel data={departures({
      disruptions: [{
        severity: "BLOQUANTE", cause: "PERTURBATION", title: "Métro 3 : Trafic interrompu",
        shortMessage: "Trafic interrompu", detail: "",
      }],
    })} revealed={{}} onReveal={() => {}} onSelectTrain={vi.fn()} onSelectLine={vi.fn()} />);

    await expectNoA11yViolations();
  });
});
