import type { DeparturesResponse } from "../api/types";
import { formatEta } from "./formatEta";
import { statusKind, statusLabel } from "./status";

interface Props {
  data: DeparturesResponse | null;
  onClose: () => void;
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

export function StopPanel({ data, onClose, onSelectTrain, onSelectLine }: Props) {
  if (!data) {
    return null;
  }
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
    <div
      style={{
        position: "absolute",
        top: 12,
        right: 12,
        width: 280,
        maxHeight: "70vh",
        overflowY: "auto",
        padding: 16,
        background: "#fff",
        borderRadius: 8,
        boxShadow: "0 2px 12px rgba(0,0,0,.2)",
        font: "14px sans-serif",
      }}
    >
      <button
        onClick={onClose}
        style={{ float: "right", border: "none", background: "none", cursor: "pointer", fontSize: 20, lineHeight: 1, padding: 4 }}
        aria-label="Fermer"
      >
        ✕
      </button>
      <h3 style={{ margin: "0 0 8px" }}>{data.stationName}</h3>
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
              style={{
                width: 18, height: 18, borderRadius: "50%", background: line.color, color: "#fff",
                font: "bold 11px sans-serif", display: "flex", alignItems: "center", justifyContent: "center",
                border: "none", padding: 0, cursor: "pointer",
              }}
            >
              {line.shortName}
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
              <ul style={{ margin: "0 0 0 16px", padding: 0, listStyle: "none" }}>
                {dir.passages.map((p) => (
                  <li key={p.journeyRef}>
                    <button
                      onClick={() => onSelectTrain?.(p.journeyRef)}
                      style={{
                        border: "none", background: "none", padding: "2px 0", cursor: "pointer",
                        font: "inherit", color: "#1d4ed8", textAlign: "left", width: "100%",
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
    </div>
  );
}
