import type { LineDisruptions, NetworkLine } from "../api/types";
import { severityMeta } from "./severity";
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
          const severity = disruption ? severityMeta(disruption.severity) : null;
          const count = counts.get(line.id) ?? 0;
          // Le nom porte l'identité, le décompte et la gravité ; l'état vient d'`aria-pressed`, le
          // redire ici le ferait annoncer deux fois. Le détail des perturbations reste dans le
          // `title`, qui a la place de le porter.
          const label = `Ligne ${line.shortName}, ${count} train${count > 1 ? "s" : ""}`
            + (severity ? `, ${severity.label}` : "");
          return (
            <button
              key={line.id}
              onClick={() => onToggle(line.id)}
              title={disruption
                ? `Ligne ${line.shortName} — ${severity!.label} : ${disruption.items.map((i) => i.title).join(" · ")}`
                : `${count} train(s) sur la ligne ${line.shortName}`}
              aria-label={label}
              aria-pressed={shown}
              className={styles.pill}
              data-severity={disruption?.severity}
            >
              <LineBadge color={line.color} shortName={line.shortName} size="s" />
              {count}
              {severity && <span className={styles.glyph}>{severity.glyph}</span>}
            </button>
          );
        })}
      </div>
    </>
  );
}
