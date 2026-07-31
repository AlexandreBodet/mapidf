import type { NetworkLine } from "../api/types";

/** Ordre humain : 1, 2, 3, 3b, 4… 14 — et non l'ordre alphabétique, qui mettrait 14 avant 3. */
export function humanOrder(a: NetworkLine, b: NetworkLine): number {
  const num = (id: string) => Number.parseInt(id, 10) || Number.MAX_SAFE_INTEGER;
  return num(a.id) - num(b.id) || a.id.localeCompare(b.id);
}
