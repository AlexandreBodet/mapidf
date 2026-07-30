import { useState } from "react";
import type { LineDisruptions, NetworkLine } from "../api/types";
import { severityStyle } from "./severity";

interface Props {
  lines: NetworkLine[];
  counts: Map<string, number>;
  /** Perturbations en cours par ligne ; une ligne absente n'a rien à signaler. */
  disruptions: Map<string, LineDisruptions>;
  /** null = toutes les lignes visibles. */
  visible: Set<string> | null;
  /** Horodatage du dernier snapshot servi par `/vehicles` ; null avant le premier poll. */
  asOf: string | null;
  /** Dernier poll `/vehicles` en échec : ce qui est affiché ne bouge plus. */
  stale: boolean;
  onToggle: (lineId: string) => void;
  onShowAll: () => void;
}

/** Ordre humain : 1, 2, 3, 3b, 4… 14 — et non l'ordre alphabétique, qui mettrait 14 avant 3. */
function humanOrder(a: NetworkLine, b: NetworkLine): number {
  const num = (id: string) => Number.parseInt(id, 10) || Number.MAX_SAFE_INTEGER;
  return num(a.id) - num(b.id) || a.id.localeCompare(b.id);
}

export function LinePicker({ lines, counts, disruptions, visible, asOf, stale, onToggle, onShowAll }: Props) {
  const [showDisruptions, setShowDisruptions] = useState(false);
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
          onClick={() => setShowDisruptions((open) => !open)}
          style={{
            marginTop: 6, padding: 0, border: "none", background: "none", cursor: "pointer",
            font: "inherit", color: "#b45309", textAlign: "left",
          }}
          aria-expanded={showDisruptions}
        >
          {disrupted.length === 1
            ? "1 ligne perturbée"
            : `${disrupted.length} lignes perturbées`}
          {showDisruptions ? " ▾" : " ▸"}
        </button>
      )}
      {showDisruptions && (
        <ul style={{ margin: "4px 0 0", padding: 0, listStyle: "none" }}>
          {disrupted.map((line) => {
            const disruption = disruptions.get(line.id)!;
            const style = severityStyle(disruption.severity);
            return (
              <li key={line.id} style={{ margin: "4px 0", display: "flex", gap: 6 }}>
                <span style={{ color: style.color, fontWeight: 700 }}>{style.glyph}</span>
                <span style={{ color: "#444" }}>
                  {/* Le titre du flux commence déjà par « Métro 13 : … », inutile de répéter
                      l'indice de ligne. */}
                  {disruption.items.map((item) => item.title).join(" · ")}
                </span>
              </li>
            );
          })}
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
