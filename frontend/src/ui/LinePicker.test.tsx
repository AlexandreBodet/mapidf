// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { LineDisruptions, NetworkLine } from "../api/types";
import { expectNoA11yViolations } from "../test/axe";
import { LinePicker } from "./LinePicker";

afterEach(cleanup);

const LIGNE_9: NetworkLine = { id: "C01379", shortName: "9", color: "#D5C900", mode: "METRO" };
const LIGNE_8: NetworkLine = { id: "C01378", shortName: "8", color: "#CEADD2", mode: "METRO" };

const perturbation = (severity: LineDisruptions["severity"], titre: string): LineDisruptions => ({
  lineId: LIGNE_8.id,
  severity,
  items: [{ severity, cause: "PERTURBATION", title: titre, shortMessage: "Trafic", detail: "" }],
});

function renderPicker(props: Partial<Parameters<typeof LinePicker>[0]> = {}) {
  const onToggle = vi.fn();
  const result = render(
    <LinePicker
      lines={[LIGNE_9, LIGNE_8]}
      disrupted={[]}
      counts={new Map([[LIGNE_9.id, 12], [LIGNE_8.id, 7]])}
      disruptions={new Map()}
      disruptionsOpen={false}
      visible={null}
      onToggle={onToggle}
      {...props}
    />,
  );
  return { onToggle, ...result };
}

describe("LinePicker", () => {
  it("compte les trains de chaque ligne, pastille et compteur", () => {
    renderPicker();

    // Deux boutons, un par ligne suivie ; le shortName vit dans la pastille. On lit ici le contenu
    // textuel, pas le nom accessible : celui-ci vient désormais d'un `aria-label` (cas suivants).
    expect(screen.getAllByRole("button")).toHaveLength(2);
    expect(screen.getByTitle(/ligne 9/).textContent).toBe("912");
    expect(screen.getByTitle(/ligne 8/).textContent).toBe("87");
  });

  it("bascule la ligne cliquée, et elle seule", () => {
    const { onToggle } = renderPicker();

    fireEvent.click(screen.getByTitle(/ligne 8/));

    expect(onToggle).toHaveBeenCalledExactlyOnceWith(LIGNE_8.id);
  });

  it("remplace le compte par la perturbation dans l'infobulle, et ajoute son glyphe", () => {
    // Une information portée par la seule couleur est illisible : le glyphe la double.
    renderPicker({
      disruptions: new Map([[LIGNE_8.id, perturbation("BLOQUANTE", "Métro 8 : Trafic interrompu")]]),
    });

    const pastille = screen.getByTitle(/trafic bloqué/);
    expect(pastille.getAttribute("title")).toContain("Métro 8 : Trafic interrompu");
    expect(pastille.textContent).toContain("✕");
    // La ligne 9 n'a rien : son infobulle reste le compteur de trains.
    expect(screen.getByTitle(/12 train\(s\)/)).not.toBeNull();
  });

  it("ne déplie la liste des perturbations que sur demande", () => {
    const disruptions = new Map([
      [LIGNE_8.id, perturbation("PERTURBEE", "Métro 8 : Incident - Trafic ralenti")],
    ]);
    const replie = renderPicker({ disruptions, disrupted: [LIGNE_8], disruptionsOpen: false });
    expect(screen.queryByText(/Incident - Trafic ralenti/)).toBeNull();
    replie.unmount();

    renderPicker({ disruptions, disrupted: [LIGNE_8], disruptionsOpen: true });
    // Titre raccourci par disruptionTitle : la pastille de ligne porte déjà « Métro 8 ».
    expect(screen.queryByText("Incident - Trafic ralenti")).not.toBeNull();
  });

  it("ne présente aucune violation détectable par axe", async () => {
    renderPicker({
      disruptions: new Map([[LIGNE_8.id, perturbation("BLOQUANTE", "Métro 8 : Trafic interrompu")]]),
      disrupted: [LIGNE_8],
      disruptionsOpen: true,
      visible: new Set([LIGNE_9.id]),
    });

    await expectNoA11yViolations();
  });

  it("expose l'état affiché/masqué de chaque ligne", () => {
    // `data-shown` ne disait l'état qu'au CSS : au lecteur d'écran, une ligne masquée était
    // indiscernable d'une ligne affichée.
    renderPicker({ visible: new Set([LIGNE_9.id]) });

    expect(screen.getByTitle(/ligne 9/).getAttribute("aria-pressed")).toBe("true");
    expect(screen.getByTitle(/ligne 8/).getAttribute("aria-pressed")).toBe("false");
  });

  it("nomme la pastille au lieu de laisser lire « 912 »", () => {
    // Le `title` n'est qu'un dernier recours dans le calcul du nom accessible : le contenu
    // textuel l'emportait, donc la pastille s'annonçait « 912 ».
    renderPicker();

    expect(screen.getByRole("button", { name: "Ligne 9, 12 trains" })).not.toBeNull();
    expect(screen.getByRole("button", { name: "Ligne 8, 7 trains" })).not.toBeNull();
  });

  it("annonce la gravité dans le nom, sans y déverser les titres", () => {
    renderPicker({
      disruptions: new Map([[LIGNE_8.id, perturbation("BLOQUANTE", "Métro 8 : Trafic interrompu")]]),
    });

    const pastille = screen.getByRole("button", { name: "Ligne 8, 7 trains, trafic bloqué" });
    // Le détail reste dans l'infobulle, qui a la place de le porter.
    expect(pastille.getAttribute("title")).toContain("Métro 8 : Trafic interrompu");
  });

  it("accorde le nom au singulier", () => {
    renderPicker({ counts: new Map([[LIGNE_9.id, 1], [LIGNE_8.id, 0]]) });

    expect(screen.getByRole("button", { name: "Ligne 9, 1 train" })).not.toBeNull();
    expect(screen.getByRole("button", { name: "Ligne 8, 0 train" })).not.toBeNull();
  });
});
