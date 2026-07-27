import type { DeparturesResponse } from "../api/types";
import { formatEta } from "./formatEta";

interface Props {
  data: DeparturesResponse | null;
  onClose: () => void;
  onSelectTrain?: (tripId: string) => void;
}

export function StopPanel({ data, onClose, onSelectTrain }: Props) {
  if (!data) {
    return null;
  }
  return (
    <div
      style={{
        position: "absolute",
        top: 12,
        right: 12,
        width: 260,
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
      {data.directions.length === 0 && (
        <p style={{ margin: "4px 0", color: "#666" }}>Aucun passage annoncé.</p>
      )}
      {data.directions.map((dir) => (
        <div key={dir.destination} style={{ margin: "8px 0 0" }}>
          <p style={{ margin: "0 0 2px", fontWeight: 600 }}>→ {dir.destination}</p>
          <ul style={{ margin: "0 0 0 16px", padding: 0, listStyle: "none" }}>
            {dir.passages.map((p, i) => (
              <li key={i}>
                <button
                  onClick={() => onSelectTrain?.(p.journeyRef)}
                  style={{
                    border: "none", background: "none", padding: "2px 0", cursor: "pointer",
                    font: "inherit", color: "#1d4ed8", textAlign: "left", width: "100%",
                  }}
                  title="Suivre ce métro"
                >
                  {formatEta(p.expectedTime)}
                </button>
              </li>
            ))}
          </ul>
        </div>
      ))}
    </div>
  );
}
