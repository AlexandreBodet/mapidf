import { useState } from "react";
import type { LineDisruptions, NetworkLine } from "../api/types";
import { severityStyle } from "./severity";

interface Props {
  lines: NetworkLine[];
  counts: Map<string, number>;
  /** Perturbations en cours par ligne ; une ligne absente n'a rien à signaler. */
  disruptions: Map<string, LineDisruptions>;
  /** Liste des perturbations ouverte. Piloté par App : la carte s'en sert pour l'emphase. */
  disruptionsOpen: boolean;
  onToggleDisruptions: () => void;
  /** null = toutes les lignes visibles. */
  visible: Set<string> | null;
  /** Horodatage du dernier snapshot servi par `/vehicles` ; null avant le premier poll. */
  asOf: string | null;
  /** Dernier poll `/vehicles` en échec : ce qui est affiché ne bouge plus. */
  stale: boolean;
  onToggle: (lineId: string) => void;
  onShowAll: () => void;
}

/**
 * « Métro 13 : Travaux - Arrêt non desservi » → « Travaux - Arrêt non desservi ». Le flux
 * préfixe ses titres par le mode et l'indice ; la pastille les porte déjà. Titre inchangé si
 * le format diffère.
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

/** Ordre humain : 1, 2, 3, 3b, 4… 14 — et non l'ordre alphabétique, qui mettrait 14 avant 3. */
function humanOrder(a: NetworkLine, b: NetworkLine): number {
  const num = (id: string) => Number.parseInt(id, 10) || Number.MAX_SAFE_INTEGER;
  return num(a.id) - num(b.id) || a.id.localeCompare(b.id);
}

export function LinePicker({
  lines, counts, disruptions, disruptionsOpen, onToggleDisruptions,
  visible, asOf, stale, onToggle, onShowAll,
}: Props) {
  // Quelles perturbations ont leur détail déplié. Fermé par défaut : le détail fait souvent
  // plusieurs lignes, et l'essentiel tient dans le badge.
  const [openDetails, setOpenDetails] = useState<Set<string>>(new Set());
  const toggleDetail = (key: string) =>
    setOpenDetails((current) => {
      const next = new Set(current);
      if (!next.delete(key)) {
        next.add(key);
      }
      return next;
    });
  const total = [...counts.values()].reduce((sum, n) => sum + n, 0);
  const sorted = [...lines].sort(humanOrder);
  // Ordre humain aussi dans la liste : elle se lit à côté des pastilles.
  const disrupted = sorted.filter((line) => disruptions.has(line.id));
  return (
    <div
      style={{
        position: "absolute",
        bottom: 12,
        left: 12,
        padding: "10px 12px",
        background: "#fff",
        borderRadius: 8,
        boxShadow: "0 2px 12px rgba(0,0,0,.2)",
        font: "13px sans-serif",
        maxWidth: 300,
      }}
    >
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", gap: 8 }}>
        <b>{total} trains en circulation</b>
        {visible && (
          <button
            onClick={onShowAll}
            style={{ border: "none", background: "none", color: "#1d4ed8", cursor: "pointer", font: "inherit" }}
          >
            tout afficher
          </button>
        )}
      </div>
      {disrupted.length > 0 && (
        <button
          onClick={onToggleDisruptions}
          style={{
            marginTop: 6, padding: 0, border: "none", background: "none", cursor: "pointer",
            font: "inherit", color: "#b45309", textAlign: "left",
          }}
          aria-expanded={disruptionsOpen}
        >
          {disrupted.length === 1
            ? "1 ligne perturbée"
            : `${disrupted.length} lignes perturbées`}
          {disruptionsOpen ? " ▾" : " ▸"}
        </button>
      )}
      {disruptionsOpen && (
        <ul style={{ margin: "6px 0 0", padding: 0, listStyle: "none" }}>
          {disrupted.flatMap((line) =>
            disruptions.get(line.id)!.items.map((item, index) => {
              const style = severityStyle(item.severity);
              const key = `${line.id}-${index}`;
              return (
                <li
                  key={key}
                  style={{
                    display: "flex", gap: 6, alignItems: "flex-start",
                    padding: "6px 0", borderTop: "1px solid #eee",
                  }}
                >
                  <span
                    style={{
                      flex: "0 0 auto", width: 18, height: 18, borderRadius: "50%",
                      background: line.color, color: "#fff", font: "bold 11px sans-serif",
                      display: "flex", alignItems: "center", justifyContent: "center",
                    }}
                  >
                    {line.shortName}
                  </span>
                  <span style={{ minWidth: 0 }}>
                    {/* shortMessage est le résumé fourni par le flux (« Trafic interrompu ») :
                        en badge plein, il porte la gravité sans glyphe filiforme. */}
                    <span
                      style={{
                        display: "inline-block", padding: "1px 6px", borderRadius: 4,
                        background: style.color, color: "#fff", font: "bold 11px sans-serif",
                      }}
                    >
                      {badgeText(item.shortMessage, style.label)}
                    </span>
                    {/* Le titre répète l'indice de ligne (« Métro 13 : … »), déjà porté par la
                        pastille : on n'en garde que la cause. Cliquable seulement s'il y a un
                        détail à révéler — sinon le curseur mentirait. */}
                    {item.detail ? (
                      <button
                        onClick={() => toggleDetail(key)}
                        aria-expanded={openDetails.has(key)}
                        style={{
                          border: "none", background: "none", padding: 0, marginLeft: 6,
                          font: "inherit", color: "#1d4ed8", cursor: "pointer", textAlign: "left",
                        }}
                      >
                        {withoutLinePrefix(item.title)}{openDetails.has(key) ? " ▾" : " ▸"}
                      </button>
                    ) : (
                      <span style={{ color: "#444", marginLeft: 6 }}>{withoutLinePrefix(item.title)}</span>
                    )}
                    {openDetails.has(key) && (
                      // `pre-line` : le texte brut du serveur garde ses sauts de ligne. Hauteur
                      // bornée, certains messages font un paragraphe entier.
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
            }),
          )}
        </ul>
      )}
      <div style={{ display: "flex", flexWrap: "wrap", gap: 6, marginTop: 8 }}>
        {sorted.map((line) => {
          const shown = !visible || visible.has(line.id);
          const disruption = disruptions.get(line.id);
          const style = disruption ? severityStyle(disruption.severity) : null;
          return (
            <button
              key={line.id}
              onClick={() => onToggle(line.id)}
              title={disruption
                ? `Ligne ${line.shortName} — ${style!.label} : ${disruption.items.map((i) => i.title).join(" · ")}`
                : `${counts.get(line.id) ?? 0} train(s) sur la ligne ${line.shortName}`}
              style={{
                display: "flex",
                alignItems: "center",
                gap: 4,
                padding: "2px 6px",
                border: `1px solid ${style ? style.color : "#ddd"}`,
                borderRadius: 12,
                background: shown ? "#fff" : "#f3f3f3",
                opacity: shown ? 1 : 0.45,
                cursor: "pointer",
                font: "12px sans-serif",
              }}
            >
              <span
                style={{
                  width: 16,
                  height: 16,
                  borderRadius: "50%",
                  background: line.color,
                  color: "#fff",
                  font: "bold 10px sans-serif",
                  display: "flex",
                  alignItems: "center",
                  justifyContent: "center",
                }}
              >
                {line.shortName}
              </span>
              {counts.get(line.id) ?? 0}
              {style && <span style={{ color: style.color, fontWeight: 700 }}>{style.glyph}</span>}
            </button>
          );
        })}
      </div>
      {stale && (
        <div style={{ color: "#b45309", marginTop: 6 }} role="status">
          ⚠ Positions plus mises à jour — la connexion au service est interrompue.
        </div>
      )}
      <div style={{ color: "#666", marginTop: 6 }}>
        Position estimée (pas de GPS en métro). Les trains atténués ont un placement approximatif.
        {/* Date de mise à jour de la donnée : l'article 5.7 de la Licence Mobilité (« neutralité
            et loyauté ») interdit d'induire en erreur sur le contenu ET sur sa date de mise à
            jour. Le disclaimer ci-dessus couvre la nature estimée, cette ligne la fraîcheur. */}
        {asOf && ` Données IDFM du ${new Date(asOf).toLocaleTimeString("fr-FR")}.`}
      </div>
    </div>
  );
}
