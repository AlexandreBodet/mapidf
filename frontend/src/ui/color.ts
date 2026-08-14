const WHITE = "#ffffff";
/** Pas `#000` : `#111` est déjà la couleur de texte du projet (`--text`, libellés de station). */
const NEAR_BLACK = "#111111";

/** Composantes 0-255 d'un `#rgb` ou `#rrggbb`. Une composante illisible vaut zéro : le flux sert la
 *  couleur, et un tracé doit rester dessiné même si elle est cassée. */
function channels(hex: string): [number, number, number] {
  const value = hex.replace("#", "");
  const full = value.length === 3 ? value.split("").map((c) => c + c).join("") : value;
  const at = (offset: number) => {
    const raw = Number.parseInt(full.slice(offset, offset + 2), 16);
    return Number.isNaN(raw) ? 0 : raw;
  };
  return [at(0), at(2), at(4)];
}

/** Luminance relative WCAG 2.x. */
function luminance(hex: string): number {
  const [r, g, b] = channels(hex).map((value) => {
    const channel = value / 255;
    return channel <= 0.03928 ? channel / 12.92 : ((channel + 0.055) / 1.055) ** 2.4;
  });
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}

function contrast(a: string, b: string): number {
  const first = luminance(a);
  const second = luminance(b);
  return (Math.max(first, second) + 0.05) / (Math.min(first, second) + 0.05);
}

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
  const [r, g, b] = channels(hex).map((base) => Math.round(base * keep + 255 * (1 - keep)));
  return `rgb(${r}, ${g}, ${b})`;
}

/**
 * Avant-plan lisible sur une couleur de ligne : blanc ou quasi-noir, celui des deux qui contraste le
 * mieux.
 *
 * Les teintes officielles ne sont **pas** dessinées pour du blanc, contrairement à ce que faisait
 * `LineBadge` : mesuré sur les valeurs réelles du flux, six sur huit échouent le seuil de 4,5:1 avec
 * du blanc, jusqu'à 1,62:1 sur la ligne 9. La signalétique RATP fait l'inverse — le 9 est noir sur
 * jaune.
 *
 * Le choix se **calcule** au lieu de suivre un seuil de luminance, qui se tromperait sur les teintes
 * moyennes : la ligne 3 (`#6E6E00`) a une luminance basse et demande pourtant du blanc.
 */
export function readableOn(background: string): string {
  return contrast(WHITE, background) >= contrast(NEAR_BLACK, background) ? WHITE : NEAR_BLACK;
}
