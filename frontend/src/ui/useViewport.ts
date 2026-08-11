import { useEffect, useState } from "react";

/** Doit rester synchronisé avec la media query de `src/index.css` (variable `--tap`). */
export const NARROW_MAX_WIDTH = 720;

// Largeur seule : un téléphone en paysage (844 × 390) garde les cartes flottantes, une feuille
// sur 390 px de haut serait pire que le mal.
const NARROW = `(max-width: ${NARROW_MAX_WIDTH}px)`;

export function useIsNarrow(): boolean {
  const [narrow, setNarrow] = useState(() => window.matchMedia(NARROW).matches);
  useEffect(() => {
    const query = window.matchMedia(NARROW);
    const onChange = (event: MediaQueryListEvent) => setNarrow(event.matches);
    query.addEventListener("change", onChange);
    // Relu ici : la largeur peut avoir changé entre le premier rendu et cet effet.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setNarrow(query.matches);
    return () => query.removeEventListener("change", onChange);
  }, []);
  return narrow;
}

/**
 * Hauteur du viewport en pixels — la feuille se dimensionne en px, pas en `dvh`, parce qu'un
 * glissement doit suivre le doigt au pixel. `innerHeight` suit la barre d'outils mobile qui se
 * replie, comme `dvh` le ferait en CSS.
 */
export function useViewportHeight(): number {
  const [height, setHeight] = useState(() => window.innerHeight);
  useEffect(() => {
    const onResize = () => setHeight(window.innerHeight);
    window.addEventListener("resize", onResize);
    onResize();
    return () => window.removeEventListener("resize", onResize);
  }, []);
  return height;
}
