import { useState, type ReactNode } from "react";
import type { DisruptionItem } from "../api/types";
import { severityMeta } from "./severity";
import { badgeText, disruptionTitle } from "./disruptionText";
import styles from "./DisruptionRow.module.css";

interface Props {
  item: DisruptionItem;
  /** Contenu posé avant le badge — la pastille de ligne dans le sélecteur, rien ailleurs. */
  leading?: ReactNode;
}

/** Une perturbation, partagée par le sélecteur de lignes et la fiche station. */
export function DisruptionRow({ item, leading }: Props) {
  // Chaque ligne possède son état : le parent n'a pas à tenir un registre des détails ouverts.
  const [open, setOpen] = useState(false);
  const severity = severityMeta(item.severity);
  // `leading` n'est jamais autre chose que la pastille de ligne : sa présence EST la condition.
  const title = disruptionTitle(item.title, leading != null);
  return (
    <li className={styles.row} data-severity={item.severity}>
      {leading}
      <span className={styles.text}>
        <span className={styles.badge}>
          {badgeText(item.shortMessage, severity.label)}
        </span>
        {/* Cliquable seulement s'il y a un détail à révéler — sinon le curseur mentirait. */}
        {item.detail ? (
          <button
            onClick={() => setOpen((current) => !current)}
            aria-expanded={open}
            className={styles.toggle}
          >
            {title}{open ? " ▾" : " ▸"}
          </button>
        ) : (
          <span className={styles.title}>{title}</span>
        )}
        {open && <div className={styles.detail}>{item.detail}</div>}
      </span>
    </li>
  );
}
