/**
 * « Métro 13 : Travaux - Arrêt non desservi » → « Travaux - Arrêt non desservi », mais seulement
 * si `lineShown` : le flux préfixe ses titres par le mode et l'indice, et c'est la seule mention
 * de la ligne. Le sélecteur la porte dans sa pastille, la fiche station non — elle n'a que le nom
 * de la station, et à République (5 lignes) « Arrêt non desservi » ne disait pas laquelle.
 * Titre inchangé si le format du flux diffère.
 */
export function disruptionTitle(title: string, lineShown: boolean): string {
  const separator = title.indexOf(" : ");
  return lineShown && separator > 0 ? title.slice(separator + 3) : title;
}

/**
 * Le flux met « Autre » en résumé quand il n'en a pas — mesuré sur « Métro 14 / 5 / 4 :
 * Information - Autre », dont tout le sens était dans le message. Le libellé de gravité en dit
 * alors davantage.
 */
export function badgeText(shortMessage: string, fallback: string): string {
  return !shortMessage || shortMessage.toLowerCase() === "autre" ? fallback : shortMessage;
}
