import { describe, expect, it } from "vitest";
import type { NetworkLine } from "../api/types";
import { humanOrder } from "./lineOrder";

const line = (id: string): NetworkLine =>
  ({ id, shortName: id, color: "#000" }) as NetworkLine;

describe("humanOrder", () => {
  it("classe 3 avant 14, contrairement à l'ordre alphabétique", () => {
    const sorted = [line("14"), line("3"), line("1")].sort(humanOrder).map((l) => l.id);
    expect(sorted).toEqual(["1", "3", "14"]);
  });

  it("place les lignes bis juste après leur numéro", () => {
    const sorted = [line("7b"), line("7"), line("3b"), line("3")].sort(humanOrder).map((l) => l.id);
    expect(sorted).toEqual(["3", "3b", "7", "7b"]);
  });
});
