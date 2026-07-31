import type { DeparturesResponse } from "../api/types";
import { formatEta } from "./formatEta";
import { statusKind, statusLabel } from "./status";
import { DisruptionRow } from "./DisruptionRow";

interface Props {
  data: DeparturesResponse;
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
  const cancelled = kind === "cancelled";
  return (
    <span
      style={{
        marginLeft: 6,
        padding: "0 5px",
        borderRadius: 8,
        background: cancelled ? "#fecaca" : "#fde68a",
        color: cancelled ? "#991b1b" : "#92400e",
        font: "bold 11px sans-serif",
      }}
    >
      {statusLabel(status)}
    </span>
  );
}

export function StopPanel({ data, onSelectTrain, onSelectLine }: Props) {
  // Le panneau peut vieillir entre deux rafraîchissements : on masque les passages déjà partis
  // et les groupes qui n'ont plus rien à venir.
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
        <ul style={{ margin: "0 0 4px", padding: 0, listStyle: "none" }}>
          {data.disruptions.map((item, index) => (
            <DisruptionRow key={index} item={item} />
          ))}
        </ul>
      )}
      {lines.length === 0 && (
        <p style={{ margin: "4px 0", color: "#666" }}>Aucun passage annoncé.</p>
      )}
      {lines.map((line) => (
        <div key={line.lineId} style={{ margin: "10px 0 0" }}>
          <div style={{ display: "flex", alignItems: "center", gap: 6 }}>
            <button
              onClick={() => onSelectLine?.(line.lineId)}
              // Isolement inconditionnel, comme un clic dans le sélecteur du bas : quel que
              // soit le filtre courant, ce clic ne laisse que cette ligne (décision produit).
              title={`N'afficher que la ligne ${line.shortName}`}
              aria-label={`N'afficher que la ligne ${line.shortName}`}
              // La cible tactile est le bouton, transparent ; la pastille reste ronde dans son
              // span. Porter `minHeight` sur le carré de 18 px en faisait une ellipse verticale.
              style={{
                display: "flex", alignItems: "center", justifyContent: "center",
                border: "none", background: "none", padding: 0, cursor: "pointer",
                minHeight: "var(--tap)",
              }}
            >
              <span
                style={{
                  width: 18, height: 18, borderRadius: "50%", background: line.color, color: "#fff",
                  font: "bold 11px sans-serif", display: "flex", alignItems: "center",
                  justifyContent: "center",
                }}
              >
                {line.shortName}
              </span>
            </button>
          </div>
          {line.directions.map((dir) => (
            // `destination` suffit comme clé : StationDepartureService.directionsOf groupe les
            // passages dans une Map<destination, …> PAR LIGNE, donc les destinations sont uniques
            // par construction au sein d'un LineDepartures — y compris sur une ligne à
            // embranchement. Y adjoindre l'index rendrait la clé instable dès qu'une destination
            // apparaît ou disparaît entre deux polls, et remonterait les sous-arbres pour rien.
            <div key={dir.destination} style={{ margin: "4px 0 0 4px" }}>
              <p style={{ margin: "0 0 2px", fontWeight: 600 }}>→ {dir.destination}</p>
              {/* Rangée plutôt qu'empilement : à 44 px par cible tactile, trois passages
                  empilés prenaient 132 px par direction (retour recette). */}
              <ul
                style={{
                  margin: "0 0 0 16px", padding: 0, listStyle: "none",
                  display: "flex", flexWrap: "wrap", alignItems: "center",
                }}
              >
                {dir.passages.map((p, index) => (
                  <li key={p.journeyRef} style={{ display: "flex", alignItems: "center" }}>
                    {index > 0 && <span aria-hidden="true" style={{ color: "#bbb", margin: "0 4px" }}>·</span>}
                    <button
                      onClick={() => onSelectTrain?.(p.journeyRef)}
                      style={{
                        border: "none", background: "none", padding: "2px 4px", cursor: "pointer",
                        font: "inherit", color: "#1d4ed8", textAlign: "left",
                        minHeight: "var(--tap)",
                      }}
                      title="Suivre ce métro"
                    >
                      <span
                        style={{
                          textDecoration:
                            statusKind(p.status) === "cancelled" ? "line-through" : undefined,
                        }}
                      >
                        {formatEta(p.expectedTime)}
                      </span>
                      <StatusBadge status={p.status} />
                    </button>
                  </li>
                ))}
              </ul>
            </div>
          ))}
        </div>
      ))}
    </>
  );
}
