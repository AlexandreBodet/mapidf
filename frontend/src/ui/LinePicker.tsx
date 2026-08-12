import type { LineDisruptions, NetworkLine } from "../api/types";
import { severityStyle } from "./severity";
import { DisruptionRow } from "./DisruptionRow";
import { LineBadge } from "./LineBadge";
import styles from "./LinePicker.module.css";

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
  onToggle: (lineId: string) => void;
}

export function LinePicker({
  lines, disrupted, counts, disruptions, disruptionsOpen, visible, onToggle,
}: Props) {
  return (
    <>
      {disruptionsOpen && disrupted.length > 0 && (
        <ul className={styles.list}>
          {disrupted.flatMap((line) =>
            disruptions.get(line.id)!.items.map((item, index) => (
              <DisruptionRow
                key={`${line.id}-${index}`}
                item={item}
                leading={<LineBadge color={line.color} shortName={line.shortName} size="m" />}
              />
            )),
          )}
        </ul>
      )}
      <div className={styles.pills}>
        {lines.map((line) => {
          const shown = !visible || visible.has(line.id);
          const disruption = disruptions.get(line.id);
          const severity = disruption ? severityStyle(disruption.severity) : null;
          return (
            <button
              key={line.id}
              onClick={() => onToggle(line.id)}
              title={disruption
                ? `Ligne ${line.shortName} — ${severity!.label} : ${disruption.items.map((i) => i.title).join(" · ")}`
                : `${counts.get(line.id) ?? 0} train(s) sur la ligne ${line.shortName}`}
              className={styles.pill}
              data-shown={shown}
              data-severity={disruption?.severity}
            >
              <LineBadge color={line.color} shortName={line.shortName} size="s" />
              {counts.get(line.id) ?? 0}
              {severity && <span className={styles.glyph}>{severity.glyph}</span>}
            </button>
          );
        })}
      </div>
    </>
  );
}
