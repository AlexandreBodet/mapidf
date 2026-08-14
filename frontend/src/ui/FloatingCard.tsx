import type { ReactNode } from "react";
import styles from "./FloatingCard.module.css";

type Anchor = "top-right" | "bottom-left";

interface Props {
  anchor: Anchor;
  /** Nom de la région, lu par les technologies d'assistance. Obligatoire : une `<section>` sans nom
   *  n'est pas une région et n'apparaît dans aucun plan de document. */
  label: string;
  /** Ce que ce panneau-là fait différemment (padding, police, largeur), fourni par le parent. */
  className?: string;
  children: ReactNode;
}

/**
 * Carte flottante du rendu large. Existe pour que les panneaux ignorent où ils sont posés : sur
 * écran étroit c'est `Sheet` qui les accueille, sans qu'ils aient à le savoir — et comme `Sheet`,
 * c'est une `<section>` nommée, pour que les deux mises en page se présentent de la même façon.
 */
export function FloatingCard({ anchor, label, className, children }: Props) {
  return (
    <section
      aria-label={label}
      className={className ? `${styles.card} ${className}` : styles.card}
      data-anchor={anchor}
    >
      {children}
    </section>
  );
}
