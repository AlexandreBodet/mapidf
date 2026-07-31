export type Cran = "apercu" | "moitie" | "plein";

/** Poignée (44 px, seuil tactile) + ligne de résumé (52 px) : ce qui reste visible replié. */
export const PEEK_HEIGHT = 96;

/** Du plus bas au plus haut : l'ordre porte la notion de « cran suivant ». */
const ORDER: Cran[] = ["apercu", "moitie", "plein"];

const RATIO: Record<Cran, number> = { apercu: 0, moitie: 0.5, plein: 0.9 };

/** Au-delà de cette vitesse (px/ms), le geste décide du sens et la position ne compte plus. */
const FLICK = 0.5;

/** Part maximale de la hauteur retirée à la caméra (cf. mapPadding). */
const MAX_PADDING_RATIO = 0.45;

export function cranHeight(cran: Cran, viewportHeight: number): number {
  // Le plancher vaut pour tous les crans : sur un écran très court, 50 % passerait sous la
  // poignée et la feuille rétrécirait en s'ouvrant.
  return Math.max(PEEK_HEIGHT, Math.round(viewportHeight * RATIO[cran]));
}

export function nextCran(cran: Cran): Cran {
  return ORDER[(ORDER.indexOf(cran) + 1) % ORDER.length];
}

/** Voisin borné aux extrémités — contrairement à `nextCran`, qui boucle. */
function neighbour(cran: Cran, direction: 1 | -1): Cran {
  const index = ORDER.indexOf(cran) + direction;
  return ORDER[Math.min(ORDER.length - 1, Math.max(0, index))];
}

export function snap(
  heightPx: number,
  velocityPxPerMs: number,
  viewportHeight: number,
  from: Cran,
): Cran {
  if (velocityPxPerMs > FLICK) {
    return neighbour(from, 1);
  }
  if (velocityPxPerMs < -FLICK) {
    return neighbour(from, -1);
  }
  const distance = (cran: Cran) => Math.abs(cranHeight(cran, viewportHeight) - heightPx);
  return ORDER.reduce((best, cran) => (distance(cran) < distance(best) ? cran : best));
}

/**
 * Hauteur dont la caméra doit se décaler pour que ses recentrages tombent au-dessus de la
 * feuille. Plafonnée : au cran plein, retirer 90 % de la hauteur ne laisserait à MapLibre
 * qu'une bande de quelques dizaines de pixels pour calculer un centre.
 */
export function mapPadding(cran: Cran, viewportHeight: number): number {
  return Math.min(cranHeight(cran, viewportHeight), Math.round(viewportHeight * MAX_PADDING_RATIO));
}
