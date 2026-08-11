// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { Vehicle } from "../api/types";
import { VehiclePanel } from "./VehiclePanel";

afterEach(cleanup);

// +95 s de marge : sans elle, les millisecondes écoulées entre la fixture et le rendu font tomber
// le calcul de formatEta sur la minute inférieure et le test devient intermittent.
const vehicle = (overrides: Partial<Vehicle> = {}): Vehicle => ({
  journeyRef: "J-1", lineId: "9", lat: 48.87, lng: 2.33, bearing: 0,
  status: "ON_TIME", headsign: "Pont de Sèvres", nextStop: "Havre-Caumartin",
  expectedTime: new Date(Date.now() + 95_000).toISOString(),
  recordedAt: null, confidence: "RELIABLE",
  ...overrides,
});

describe("VehiclePanel", () => {
  it("montre le prochain arrêt, l'arrivée à la seconde et l'état", () => {
    // La fiche d'un train n'en montre qu'un : c'est le seul endroit où la forme en phrase
    // (« dans 1 min 35 s ») a sa place, contrairement aux listes de StopPanel.
    render(<VehiclePanel vehicle={vehicle()} />);

    expect(screen.queryByText("Havre-Caumartin")).not.toBeNull();
    expect(screen.queryByText(/dans 1 min/)).not.toBeNull();
    expect(screen.queryByText(/à l'heure/)).not.toBeNull();
    // Le métro n'a pas de GPS : la position est toujours estimée, jamais mesurée.
    expect(screen.queryByText("Position : estimée (horaire)")).not.toBeNull();
  });

  it("n'avertit d'un placement approximatif que pour une course à un seul appel", () => {
    const fiable = render(<VehiclePanel vehicle={vehicle()} />);
    expect(screen.queryByText(/Position approximative/)).toBeNull();
    fiable.unmount();

    render(<VehiclePanel vehicle={vehicle({ confidence: "APPROXIMATE" })} />);
    expect(screen.queryByText(/n'annonce qu'un seul arrêt pour ce train/)).not.toBeNull();
  });

  it("ne cite la fraîcheur de la course que si le flux l'a donnée", () => {
    const sans = render(<VehiclePanel vehicle={vehicle({ recordedAt: null })} />);
    expect(screen.queryByText(/Donnée du/)).toBeNull();
    sans.unmount();

    render(<VehiclePanel vehicle={vehicle({ recordedAt: "2026-08-11T14:32:10Z" })} />);
    expect(screen.queryByText(/Donnée du/)).not.toBeNull();
  });

  it("bascule le suivi et le dit dans son libellé", () => {
    const onFollow = vi.fn();
    const inactif = render(<VehiclePanel vehicle={vehicle()} onFollow={onFollow} />);
    expect(screen.getByRole("button").textContent).toBe("◉ Suivre");

    fireEvent.click(screen.getByRole("button"));
    expect(onFollow).toHaveBeenCalledOnce();
    inactif.unmount();

    render(<VehiclePanel vehicle={vehicle()} following onFollow={onFollow} />);
    expect(screen.getByRole("button").textContent).toBe("◉ Suivi actif");
  });
});
