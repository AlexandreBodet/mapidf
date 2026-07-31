import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { formatEta } from "./formatEta";

const NOW = new Date("2026-07-31T12:00:00Z");
const inSeconds = (sec: number) => new Date(NOW.getTime() + sec * 1000).toISOString();

beforeEach(() => {
  vi.useFakeTimers();
  vi.setSystemTime(NOW);
});

afterEach(() => {
  vi.useRealTimers();
});

describe("formatEta", () => {
  it("dit la seule minute en liste : trois horaires doivent tenir sur une ligne", () => {
    expect(formatEta(inSeconds(180))).toBe("3 min");
    expect(formatEta(inSeconds(11 * 60))).toBe("11 min");
  });

  it("garde la phrase entière quand la fiche a la place de la lire", () => {
    expect(formatEta(inSeconds(192), { withSeconds: true })).toBe("dans 3 min 12 s");
  });

  it("annonce l'imminence plutôt qu'un compte à rebours sous la minute", () => {
    expect(formatEta(inSeconds(45))).toBe("imminent");
    expect(formatEta(inSeconds(45), { withSeconds: true })).toBe("dans 45 s");
  });

  it("ne fait pas reculer un passage déjà dû", () => {
    expect(formatEta(inSeconds(-30))).toBe("imminent");
    expect(formatEta(inSeconds(-30), { withSeconds: true })).toBe("imminent / à quai");
  });

  it("ne rend pas « NaN min » sur une date illisible", () => {
    expect(formatEta("pas une date")).toBe("—");
  });
});
