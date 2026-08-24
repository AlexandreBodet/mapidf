/**
 * Combien de passages sont dépliés par direction (`StopPanel`, UX-5e), et depuis quel train le
 * surlignage carte (`App.highlightedJourneyRefs`) doit partir : les deux DOIVENT rester en phase,
 * sinon la carte surligne des trains dont le passage n'est même pas encore affiché dans la fiche
 * (mesuré : PASSAGES_PER_DIRECTION est passé de 3 à 20 côté backend pour alimenter "Voir plus",
 * et le surlignage lisait déjà toute la liste avant troncature).
 */
export const DEFAULT_REVEALED_PASSAGES = 3;
export const REVEAL_STEP = 3;

function revealKey(lineId: string, destination: string): string {
  return `${lineId}:${destination}`;
}

export function revealedCountFor(
  revealed: Record<string, number>, lineId: string, destination: string,
): number {
  return revealed[revealKey(lineId, destination)] ?? DEFAULT_REVEALED_PASSAGES;
}

export function revealMore(
  revealed: Record<string, number>, lineId: string, destination: string,
): Record<string, number> {
  const key = revealKey(lineId, destination);
  return { ...revealed, [key]: revealedCountFor(revealed, lineId, destination) + REVEAL_STEP };
}
