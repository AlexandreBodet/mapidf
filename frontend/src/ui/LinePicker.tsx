import type { LineDisruptions, NetworkLine } from "../api/types";
import { severityStyle } from "./severity";
import { DisruptionRow } from "./DisruptionRow";

interface Props {
  /** Déjà triées dans l'ordre humain par `App`. */
  lines: NetworkLine[];
  /** Sous-ensemble de `lines` ayant une perturbation en cours, même ordre. */
  disrupted: NetworkLine[];
  counts: Map<string, number>;
  /** Perturbations en cours par ligne ; une ligne absente n'a rien à signaler. */
  disruptions: Map<string, LineDisruptions>;
  /** Liste des perturbations ouverte. Piloté par App : la carte s'en sert pour l'emphase. */
  disruptionsOpen: boolean;
  /** null = toutes les lignes visibles. */
  visible: Set<string> | null;
  /** Horodatage du dernier snapshot servi par `/vehicles` ; null avant le premier poll. */
  asOf: string | null;
  onToggle: (lineId: string) => void;
}

export function LinePicker({
  lines, disrupted, counts, disruptions, disruptionsOpen, visible, asOf, onToggle,
}: Props) {
  return (
    <>
      {disruptionsOpen && disrupted.length > 0 && (
        <ul style={{ margin: "6px 0 0", padding: 0, listStyle: "none" }}>
          {disrupted.flatMap((line) =>
            disruptions.get(line.id)!.items.map((item, index) => (
              <DisruptionRow
                key={`${line.id}-${index}`}
                item={item}
                leading={
                  <span
                    style={{
                      flex: "0 0 auto", width: 18, height: 18, borderRadius: "50%",
                      background: line.color, color: "#fff", font: "bold 11px sans-serif",
                      display: "flex", alignItems: "center", justifyContent: "center",
                    }}
                  >
                    {line.shortName}
                  </span>
                }
              />
            )),
          )}
        </ul>
      )}
      <div style={{ display: "flex", flexWrap: "wrap", gap: 6, marginTop: 8 }}>
        {lines.map((line) => {
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
                minHeight: "var(--tap)",
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
      <div style={{ color: "#666", marginTop: 6 }}>
        Position estimée (pas de GPS en métro). Les trains atténués ont un placement approximatif.
        {/* Date de mise à jour de la donnée : l'article 5.7 de la Licence Mobilité (« neutralité
            et loyauté ») interdit d'induire en erreur sur le contenu ET sur sa date de mise à
            jour. Le disclaimer ci-dessus couvre la nature estimée, cette ligne la fraîcheur. */}
        {asOf && ` Données IDFM du ${new Date(asOf).toLocaleTimeString("fr-FR")}.`}
      </div>
    </>
  );
}
