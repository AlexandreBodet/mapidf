import type { CSSProperties } from "react";
import shared from "./shared.module.css";

interface Props {
  /** Couleur officielle de la ligne, servie par `/network` : 16 teintes GTFS, ensemble non borné. */
  color: string;
  shortName: string;
  /** `s` dans le sélecteur (16 px), `m` en tête d'une perturbation et dans la fiche station. */
  size: "s" | "m";
}

/**
 * Pastille ronde d'une ligne. Extraite de ses trois copies à l'occasion de QUA-8 : garder le style
 * inline dupliqué était supportable, un module CSS dupliqué trois fois ne l'est pas.
 *
 * L'assertion sur `style` est nécessaire : `CSSProperties` ne connaît pas les variables CSS et
 * `tsc` refuse la propriété (TS2353) sans elle.
 */
export function LineBadge({ color, shortName, size }: Props) {
  return (
    <span
      className={shared.lineBadge}
      data-size={size}
      style={{ "--line-color": color } as CSSProperties}
    >
      {shortName}
    </span>
  );
}
