/**
 * Éclaircit une couleur de ligne pour le tracé de fond.
 *
 * Reproduit le rendu de l'ancien `line-opacity: 0.45` sur fond clair, mais de façon
 * **idempotente sous superposition** : deux branches d'une même ligne partagent leur tronc
 * (~15 km sur 21 pour la 7), donc celui-ci est dessiné deux fois exactement superposé. Avec
 * une opacité de 0,45, l'opacité résultante monterait à ~0,70 et le tronc commun de la 7, de
 * la 13 et de la 10 apparaîtrait visiblement plus foncé que le reste du réseau.
 */
export function lightenForTrack(hex: string, keep = 0.45): string {
  const value = hex.replace("#", "");
  const full = value.length === 3 ? value.split("").map((c) => c + c).join("") : value;
  const channel = (offset: number) => {
    const raw = Number.parseInt(full.slice(offset, offset + 2), 16);
    const base = Number.isNaN(raw) ? 0 : raw;
    return Math.round(base * keep + 255 * (1 - keep));
  };
  return `rgb(${channel(0)}, ${channel(2)}, ${channel(4)})`;
}
