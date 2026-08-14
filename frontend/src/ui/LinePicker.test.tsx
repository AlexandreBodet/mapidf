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

    // Deux boutons, un par ligne suivie ; le shortName vit dans la pastille.
    // `getByTitle` et non `getByRole(…, { name })` : ce bouton n'a pas d'`aria-label`, donc son nom
    // accessible est son contenu textuel (« 912 »), pas son infobulle.
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
});
