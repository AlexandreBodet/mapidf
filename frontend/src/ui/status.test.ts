import { describe, expect, it } from "vitest";
import { statusKind, statusLabel } from "./status";

describe("statusKind", () => {
  it("reconnaît les deux valeurs mesurées sur le métro", () => {
    expect(statusKind("ON_TIME")).toBe("onTime");
    expect(statusKind("DELAYED")).toBe("delayed");
  });

  it("admet les variantes d'orthographe du flux SIRI", () => {
    expect(statusKind("ONTIME")).toBe("onTime");
    expect(statusKind("EARLY")).toBe("early");
    expect(statusKind("CANCELED")).toBe("cancelled");
    expect(statusKind("CANCELLED")).toBe("cancelled");
  });

  it("ignore la casse", () => {
    expect(statusKind("on_time")).toBe("onTime");
  });

  it("tombe sur unknown plutôt que d'afficher une valeur inédite telle quelle", () => {
    // PRIM peut inventer un statut : il ne doit jamais sortir brut à l'écran.
    expect(statusKind("QUELQUE_CHOSE_DE_NEUF")).toBe("unknown");
    expect(statusKind(null)).toBe("unknown");
    expect(statusKind(undefined)).toBe("unknown");
  });
});

describe("statusLabel", () => {
  it("traduit les états connus", () => {
    expect(statusLabel("ON_TIME")).toBe("à l'heure");
    expect(statusLabel("DELAYED")).toBe("retardé");
    expect(statusLabel("EARLY")).toBe("en avance");
    expect(statusLabel("CANCELLED")).toBe("supprimé");
  });

  it("ne prétend rien sur un état inconnu", () => {
    expect(statusLabel("INEDIT")).toBe("—");
  });
});
