import { useState, type ReactNode } from "react";
import type { DisruptionItem } from "../api/types";
import { severityStyle } from "./severity";

/**
 * « Métro 13 : Travaux - Arrêt non desservi » → « Travaux - Arrêt non desservi ». Le flux
 * préfixe ses titres par le mode et l'indice, que le contexte porte déjà (pastille de ligne dans
 * le sélecteur, nom de station dans la fiche). Titre inchangé si le format diffère.
 */
function withoutLinePrefix(title: string): string {
  const separator = title.indexOf(" : ");
  return separator > 0 ? title.slice(separator + 3) : title;
}

/**
 * Le flux met « Autre » en résumé quand il n'en a pas — mesuré sur « Métro 14 / 5 / 4 :
 * Information - Autre », dont tout le sens était dans le message. Le libellé de gravité en dit
 * alors davantage.
 */
function badgeText(shortMessage: string, fallback: string): string {
  return !shortMessage || shortMessage.toLowerCase() === "autre" ? fallback : shortMessage;
}

interface Props {
  item: DisruptionItem;
  /** Contenu posé avant le badge — la pastille de ligne dans le sélecteur, rien ailleurs. */
  leading?: ReactNode;
}

/** Une perturbation, partagée par le sélecteur de lignes et la fiche station. */
export function DisruptionRow({ item, leading }: Props) {
  // Chaque ligne possède son état : le parent n'a pas à tenir un registre des détails ouverts.
  const [open, setOpen] = useState(false);
  const style = severityStyle(item.severity);
  return (
    <li
      style={{
        display: "flex", gap: 6, alignItems: "flex-start",
        padding: "6px 0", borderTop: "1px solid #eee",
      }}
    >
      {leading}
      <span style={{ minWidth: 0 }}>
        <span
          style={{
            display: "inline-block", padding: "1px 6px", borderRadius: 4,
            background: style.color, color: "#fff", font: "bold 11px sans-serif",
          }}
        >
          {badgeText(item.shortMessage, style.label)}
        </span>
        {/* Cliquable seulement s'il y a un détail à révéler — sinon le curseur mentirait. */}
        {item.detail ? (
          <button
            onClick={() => setOpen((current) => !current)}
            aria-expanded={open}
            style={{
              border: "none", background: "none", padding: 0, marginLeft: 6,
              font: "inherit", color: "#1d4ed8", cursor: "pointer", textAlign: "left",
            }}
          >
            {withoutLinePrefix(item.title)}{open ? " ▾" : " ▸"}
          </button>
        ) : (
          <span style={{ color: "#444", marginLeft: 6 }}>{withoutLinePrefix(item.title)}</span>
        )}
        {open && (
          // `pre-line` : le texte brut du serveur garde ses sauts de ligne. Hauteur bornée,
          // certains messages font un paragraphe entier.
          <div style={{
            color: "#555", marginTop: 4, whiteSpace: "pre-line",
            maxHeight: 140, overflowY: "auto",
          }}>
            {item.detail}
          </div>
        )}
      </span>
    </li>
  );
}
