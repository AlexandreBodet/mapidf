import { describe, expect, it } from "vitest";
import { toggleLine } from "./toggleLine";

// 16 lignes de métro suivies : c'est ce nombre qui décide du retour à « toutes ».
const TOTAL = 16;

describe("toggleLine", () => {
  it("isole la ligne cliquée depuis l'état « toutes »", () => {
    // Retirer une ligne sur 16 demanderait 15 clics : l'intention la plus fréquente est
    // d'en voir une seule.
    expect(toggleLine(null, "9", TOTAL)).toEqual(new Set(["9"]));
  });

  it("retire une ligne quand il en reste d'autres", () => {
    expect(toggleLine(new Set(["3", "9"]), "3", TOTAL)).toEqual(new Set(["9"]));
  });

  it("ajoute une ligne au sous-ensemble affiché", () => {
    expect(toggleLine(new Set(["3"]), "9", TOTAL)).toEqual(new Set(["3", "9"]));
  });

  it("ne vide jamais la carte : cliquer la dernière ligne visible ne fait rien", () => {
    const current = new Set(["9"]);

    // `toBe` et non `toEqual` : renvoyer un Set neuf pour un no-op déclencherait un re-render
    // et le refiltrage des 321 stations pour rien.
    expect(toggleLine(current, "9", TOTAL)).toBe(current);
  });

  it("revient à « toutes » quand le clic complète l'ensemble", () => {
    const current = new Set(["3", "9"]);

    expect(toggleLine(current, "11", 3)).toBeNull();
  });

  it("ne prétend pas « toutes » quand le réseau n'est pas encore chargé", () => {
    // lineCount = 0 : c'est l'état avant le premier /network. Le sous-ensemble doit survivre.
    expect(toggleLine(new Set(["3"]), "9", 0)).toEqual(new Set(["3", "9"]));
  });
});
