import type { ReactNode } from "react";
import styles from "./FloatingCard.module.css";

type Anchor = "top-right" | "bottom-left";

interface Props {
  anchor: Anchor;
  /** Ce que ce panneau-là fait différemment (padding, police, largeur), fourni par le parent. */
  className?: string;
  children: ReactNode;
}

/**
 * Carte flottante du rendu large. Existe pour que les panneaux ignorent où ils sont posés : sur
 * écran étroit c'est `Sheet` qui les accueille, sans qu'ils aient à le savoir.
 */
export function FloatingCard({ anchor, className, children }: Props) {
  return (
    <div className={className ? `${styles.card} ${className}` : styles.card} data-anchor={anchor}>
      {children}
    </div>
  );
}
