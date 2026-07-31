import type { CSSProperties, ReactNode } from "react";

const ANCHORS: Record<Anchor, CSSProperties> = {
  "top-right": { top: 12, right: 12 },
  "bottom-left": { bottom: 12, left: 12 },
};

type Anchor = "top-right" | "bottom-left";

interface Props {
  anchor: Anchor;
  /** Ce que ce panneau-là fait différemment (padding, font, largeur). */
  style?: CSSProperties;
  children: ReactNode;
}

/**
 * Carte flottante du rendu large. Existe pour que les panneaux ignorent où ils sont posés : sur
 * écran étroit c'est `Sheet` qui les accueille, sans qu'ils aient à le savoir.
 */
export function FloatingCard({ anchor, style, children }: Props) {
  return (
    <div
      style={{
        position: "absolute",
        ...ANCHORS[anchor],
        padding: 16,
        background: "#fff",
        borderRadius: 8,
        boxShadow: "0 2px 12px rgba(0,0,0,.2)",
        font: "14px sans-serif",
        ...style,
      }}
    >
      {children}
    </div>
  );
}
