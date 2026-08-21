// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { NetworkStation } from "../api/types";
import { expectNoA11yViolations } from "../test/axe";
import { StationSearch } from "./StationSearch";
import { searchStations } from "../api/network";

vi.mock("../api/network", () => ({
  searchStations: vi.fn(),
}));

afterEach(cleanup);

const ALPHA: NetworkStation = { id: "ST1", name: "Alpha", lat: 48.85, lng: 2.30, lineIds: ["9"] };
const GAMMA: NetworkStation = { id: "ST3", name: "Gamma", lat: 48.86, lng: 2.32, lineIds: ["7"] };

function renderSearch() {
  const onSelectStation = vi.fn();
  const result = render(<StationSearch onSelectStation={onSelectStation} />);
  return { onSelectStation, ...result };
}

describe("StationSearch", () => {
  it("n'affiche aucune liste avant la frappe", () => {
    renderSearch();

    expect(screen.queryByRole("listbox")).toBeNull();
  });

  it("affiche les résultats après la frappe, une fois le debounce écoulé", async () => {
    vi.mocked(searchStations).mockResolvedValue({ results: [ALPHA, GAMMA] });
    renderSearch();

    fireEvent.change(screen.getByRole("combobox"), { target: { value: "a" } });

    expect(await screen.findByRole("option", { name: "Alpha" })).not.toBeNull();
    expect(screen.getByRole("option", { name: "Gamma" })).not.toBeNull();
    expect(searchStations).toHaveBeenCalledWith("a", expect.any(AbortSignal));
  });

  it("la flèche bas déplace aria-activedescendant sur le premier résultat", async () => {
    vi.mocked(searchStations).mockResolvedValue({ results: [ALPHA, GAMMA] });
    renderSearch();
    const input = screen.getByRole("combobox");
    fireEvent.change(input, { target: { value: "a" } });
    await screen.findByRole("option", { name: "Alpha" });

    fireEvent.keyDown(input, { key: "ArrowDown" });

    expect(input.getAttribute("aria-activedescendant")).toBe("station-option-ST1");
    expect(screen.getByRole("option", { name: "Alpha" }).getAttribute("aria-selected")).toBe("true");
    expect(screen.getByRole("option", { name: "Gamma" }).getAttribute("aria-selected")).toBe("false");
  });

  it("Entrée sélectionne l'option courante et referme la liste", async () => {
    vi.mocked(searchStations).mockResolvedValue({ results: [ALPHA] });
    const { onSelectStation } = renderSearch();
    const input = screen.getByRole("combobox");
    fireEvent.change(input, { target: { value: "alp" } });
    await screen.findByRole("option", { name: "Alpha" });
    fireEvent.keyDown(input, { key: "ArrowDown" });

    fireEvent.keyDown(input, { key: "Enter" });

    expect(onSelectStation).toHaveBeenCalledExactlyOnceWith("ST1", [2.30, 48.85]);
    expect(screen.queryByRole("listbox")).toBeNull();
    expect((input as HTMLInputElement).value).toBe("");
  });

  it("un clic sur un résultat sélectionne aussi la station", async () => {
    vi.mocked(searchStations).mockResolvedValue({ results: [ALPHA] });
    const { onSelectStation } = renderSearch();
    fireEvent.change(screen.getByRole("combobox"), { target: { value: "alp" } });
    await screen.findByRole("option", { name: "Alpha" });

    fireEvent.click(screen.getByRole("option", { name: "Alpha" }));

    expect(onSelectStation).toHaveBeenCalledExactlyOnceWith("ST1", [2.30, 48.85]);
  });

  it("Échap referme la liste sans sélectionner", async () => {
    vi.mocked(searchStations).mockResolvedValue({ results: [ALPHA] });
    const { onSelectStation } = renderSearch();
    const input = screen.getByRole("combobox");
    fireEvent.change(input, { target: { value: "alp" } });
    await screen.findByRole("option", { name: "Alpha" });

    fireEvent.keyDown(input, { key: "Escape" });

    expect(screen.queryByRole("listbox")).toBeNull();
    expect(onSelectStation).not.toHaveBeenCalled();
  });

  it("Échap ne remonte pas au document quand la liste est ouverte", async () => {
    // Le projet ferme la fiche courante sur un Échap global (App.tsx, écouteur document). Si cet
    // événement remontait, sélectionner une station depuis la recherche fermerait aussi la fiche
    // affichée à côté sur desktop — un couplage non voulu entre deux panneaux indépendants.
    vi.mocked(searchStations).mockResolvedValue({ results: [ALPHA] });
    renderSearch();
    const documentEscape = vi.fn();
    document.addEventListener("keydown", documentEscape);
    const input = screen.getByRole("combobox");
    fireEvent.change(input, { target: { value: "alp" } });
    await screen.findByRole("option", { name: "Alpha" });

    fireEvent.keyDown(input, { key: "Escape" });

    expect(documentEscape).not.toHaveBeenCalled();
    document.removeEventListener("keydown", documentEscape);
  });

  it("Échap remonte au document quand la recherche est vide", () => {
    renderSearch();
    const documentEscape = vi.fn();
    document.addEventListener("keydown", documentEscape);

    fireEvent.keyDown(screen.getByRole("combobox"), { key: "Escape" });

    expect(documentEscape).toHaveBeenCalledOnce();
    document.removeEventListener("keydown", documentEscape);
  });

  it("un échec de recherche après navigation clavier referme la liste sans planter", async () => {
    // Régression : activeIndex pointait sur un résultat qui n'existait plus une fois `results`
    // vidé par le .catch(), et la ligne dérivant `activeId` lisait `results[activeIndex].id` sans
    // garde -> TypeError en plein rendu (pas d'error boundary dans l'appli).
    vi.mocked(searchStations)
      .mockResolvedValueOnce({ results: [ALPHA] })
      .mockRejectedValueOnce(new Error("boom"));
    renderSearch();
    const input = screen.getByRole("combobox");
    fireEvent.change(input, { target: { value: "a" } });
    await screen.findByRole("option", { name: "Alpha" });
    fireEvent.keyDown(input, { key: "ArrowDown" });

    fireEvent.change(input, { target: { value: "al" } });

    await waitFor(() => expect(screen.queryByRole("listbox")).toBeNull());
  });

  it("ne présente aucune violation détectable par axe", async () => {
    vi.mocked(searchStations).mockResolvedValue({ results: [ALPHA, GAMMA] });
    renderSearch();
    fireEvent.change(screen.getByRole("combobox"), { target: { value: "a" } });
    await screen.findByRole("option", { name: "Alpha" });

    await expectNoA11yViolations();
  });
});
