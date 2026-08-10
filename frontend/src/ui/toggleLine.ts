/**
 * Prochain sous-ensemble de lignes visibles après un clic. `null` = toutes.
 *
 * `lineCount` est un nombre, pas une liste : seule la taille sert, à décider du retour à
 * « toutes ». Extraite d'`App` pour que ses quatre règles soient testables.
 */
export function toggleLine(
  current: Set<string> | null,
  lineId: string,
  lineCount: number,
): Set<string> | null {
  // Premier clic depuis « toutes » : on isole la ligne cliquée plutôt que de la retirer d'un
  // ensemble complet — c'est l'intention la plus fréquente sur 16 lignes.
  if (current === null) {
    return new Set([lineId]);
  }
  const next = new Set(current);
  if (next.has(lineId)) {
    // Ne pas vider la carte d'un clic. `current`, pas `next` : renvoyer un Set neuf pour un
    // no-op déclencherait un re-render et le refiltrage des 321 stations pour rien.
    if (next.size === 1) {
      return current;
    }
    next.delete(lineId);
  } else {
    next.add(lineId);
  }
  return next.size === lineCount ? null : next;
}
