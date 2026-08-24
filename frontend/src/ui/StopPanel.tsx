import type { DeparturesResponse, Passage } from "../api/types";
import { formatEta } from "./formatEta";
import { statusKind, statusLabel } from "./status";
import { DisruptionRow } from "./DisruptionRow";
import { LineBadge } from "./LineBadge";
import { revealedCountFor } from "./passageReveal";
import styles from "./StopPanel.module.css";

interface Props {
  data: DeparturesResponse;
  // État de dépliage tenu par `App` (pas ici) : le surlignage carte des trains concernés
  // (`highlightedJourneyRefs`) doit rester en phase avec ce qui est effectivement listé ici,
  // ce qu'un état purement local à ce composant ne permettrait pas de garantir.
  revealed: Record<string, number>;
  onReveal: (lineId: string, destination: string) => void;
  onSelectTrain?: (journeyRef: string) => void;
  onSelectLine?: (lineId: string) => void;
}

/**
 * Un passage supprimé affichait une heure d'arrivée en bleu, indiscernable d'un train qui vient
 * — le pire cas au regard de l'art. 5.7 de la Licence Mobilité (ne pas induire en erreur sur le
 * contenu). Rien n'est affiché pour « à l'heure » : le silence dit déjà que tout va bien.
 */
function StatusBadge({ status }: { status: string }) {
  const kind = statusKind(status);
  if (kind !== "delayed" && kind !== "cancelled") {
    return null;
  }
  return (
    <span className={styles.statusBadge} data-kind={kind}>
      {statusLabel(status)}
    </span>
  );
}

export function StopPanel({ data, revealed, onReveal, onSelectTrain, onSelectLine }: Props) {
  // Le panneau peut vieillir entre deux rafraîchissements : on masque les passages déjà partis
  // et les groupes qui n'ont plus rien à venir. Un déplacement vers un effet/état changerait le
  // rythme de rafraîchissement (ce composant n'est pas concurrent/Suspense) pour corriger une
  // règle pensée pour React Compiler ; hors périmètre de ce passage de lint (QUA-5).
  // eslint-disable-next-line react-hooks/purity
  const now = Date.now();
  const lines = data.lines
    .map((line) => ({
      ...line,
      directions: line.directions
        .map((dir) => ({
          ...dir,
          passages: dir.passages.filter((p) => new Date(p.expectedTime).getTime() > now),
        }))
        .filter((dir) => dir.passages.length > 0),
    }))
    .filter((line) => line.directions.length > 0);

  return (
    <>
      {/* Perturbations visant les quais de cette station : c'est ce que l'anneau sur la carte a
          promis d'expliquer. Placé avant les passages — savoir que l'arrêt n'est pas desservi
          change la lecture des horaires qui suivent. */}
      {data.disruptions.length > 0 && (
        <ul className={styles.disruptions}>
          {data.disruptions.map((item, index) => (
            <DisruptionRow key={index} item={item} />
          ))}
        </ul>
      )}
      {lines.length === 0 && (
        <p className={styles.empty}>Aucun passage annoncé.</p>
      )}
      {lines.map((line) => (
        <div key={line.lineId} className={styles.line}>
          <div className={styles.lineHead}>
            <button
              onClick={() => onSelectLine?.(line.lineId)}
              // Isolement inconditionnel, comme un clic dans le sélecteur du bas : quel que
              // soit le filtre courant, ce clic ne laisse que cette ligne (décision produit).
              title={`N'afficher que la ligne ${line.shortName}`}
              aria-label={`N'afficher que la ligne ${line.shortName}`}
              className={styles.isolate}
            >
              <LineBadge color={line.color} shortName={line.shortName} size="m" />
            </button>
          </div>
          {line.directions.map((dir) => (
            // `destination` suffit comme clé : StationDepartureService.directionsOf groupe les
            // passages dans une Map<destination, …> PAR LIGNE, donc les destinations sont uniques
            // par construction au sein d'un LineDepartures — y compris sur une ligne à
            // embranchement. Y adjoindre l'index rendrait la clé instable dès qu'une destination
            // apparaît ou disparaît entre deux polls, et remonterait les sous-arbres pour rien.
            <DirectionRow
              key={dir.destination}
              lineId={line.lineId}
              destination={dir.destination}
              passages={dir.passages}
              revealed={revealed}
              onReveal={onReveal}
              onSelectTrain={onSelectTrain}
            />
          ))}
        </div>
      ))}
    </>
  );
}

interface DirectionRowProps {
  lineId: string;
  destination: string;
  passages: Passage[];
  revealed: Record<string, number>;
  onReveal: (lineId: string, destination: string) => void;
  onSelectTrain?: (journeyRef: string) => void;
}

function DirectionRow({
  lineId, destination, passages, revealed, onReveal, onSelectTrain,
}: DirectionRowProps) {
  const revealCount = revealedCountFor(revealed, lineId, destination);
  const visible = passages.slice(0, revealCount);
  const hasMore = passages.length > revealCount;

  return (
    <div className={styles.direction}>
      <p className={styles.destination}>→ {destination}</p>
      <ul className={styles.passages}>
        {visible.map((p, index) => (
          <li key={p.journeyRef} className={styles.passage}>
            <button
              onClick={() => onSelectTrain?.(p.journeyRef)}
              className={styles.time}
              title="Suivre ce métro"
            >
              <span
                className={styles.eta}
                data-cancelled={statusKind(p.status) === "cancelled"}
              >
                {formatEta(p.expectedTime)}
              </span>
              <StatusBadge status={p.status} />
            </button>
            {(index < visible.length - 1 || hasMore) && (
              <span aria-hidden="true" className={styles.separator}>·</span>
            )}
          </li>
        ))}
        {hasMore && (
          <li className={styles.passage}>
            <button onClick={() => onReveal(lineId, destination)} className={styles.more}>
              Voir plus
            </button>
          </li>
        )}
      </ul>
    </div>
  );
}
