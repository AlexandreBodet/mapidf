// @vitest-environment jsdom
import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { expectNoA11yViolations } from "../test/axe";
import { NetworkStatus } from "./NetworkStatus";

afterEach(cleanup);

describe("NetworkStatus", () => {
  it("s'effface dès que le plan est prêt", () => {
    render(<NetworkStatus status="ready" />);

    expect(screen.queryByRole("status")).toBeNull();
  });

  it("distingue le premier chargement d'une base encore vide", () => {
    // Sans ce bandeau, les deux donnaient le même écran blanc muet.
    const chargement = render(<NetworkStatus status="loading" />);
    // Pas de corps au chargement : le titre suffit.
    expect(screen.getByRole("status").textContent).toBe("Chargement du plan…");
    chargement.unmount();

    render(<NetworkStatus status="empty" />);
    expect(screen.queryByText("Plan en préparation")).not.toBeNull();
    expect(screen.getByRole("status").textContent).toContain("sans rien recharger");
  });

  it("dit l'échec sans jargon, et annonce la reprise automatique", () => {
    render(<NetworkStatus status="error" />);

    const banner = screen.getByRole("status");
    expect(banner.textContent).toContain("Données momentanément indisponibles");
    expect(banner.textContent).toContain("toutes les 10 secondes");
    // Le message s'adresse à quelqu'un qui veut voir passer son métro : ni « backend », ni
    // « GTFS », ni code HTTP. Le détail technique part en console (cf. useNetwork).
    expect(banner.textContent).not.toMatch(/backend|GTFS|HTTP|\b5\d{2}\b/);
  });

  it("ne présente aucune violation détectable par axe", async () => {
    render(<NetworkStatus status="error" />);

    await expectNoA11yViolations();
  });
});
