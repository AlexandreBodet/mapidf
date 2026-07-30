import { formatEta } from "./formatEta";
import { statusLabel } from "./status";
import type { Vehicle } from "../api/types";

interface Props {
  // Vehicle complet, obtenu via la Map<journeyRef, Vehicle> tenue par useVehicles : la
  // feature MapLibre cliquée ne porte plus headsign/nextStop/expectedTime/status depuis la
  // tâche 14 (allègement de la boucle de rendu), donc le panneau ne peut plus les lire
  // depuis les propriétés de la feature.
  vehicle: Vehicle | null;
  following?: boolean;
  onFollow?: () => void;
  onClose: () => void;
}

export function VehiclePanel({ vehicle, following = false, onFollow, onClose }: Props) {
  if (!vehicle) {
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
      <h3 style={{ margin: "0 0 8px" }}>→ {vehicle.headsign}</h3>
      <p style={{ margin: "4px 0" }}>
        Prochain arrêt : <b>{vehicle.nextStop}</b>
      </p>
      <p style={{ margin: "4px 0" }}>
        Arrivée estimée : <b>{formatEta(vehicle.expectedTime, { withSeconds: true })}</b>
      </p>
      <p style={{ margin: "4px 0" }}>État : {statusLabel(vehicle.status)}</p>
      {/* Le métro n'a pas de GPS : la position est TOUJOURS estimée par interpolation, jamais
          mesurée. Le backend ne peut produire aucun autre cas. */}
      <p style={{ margin: "4px 0", color: "#666" }}>Position : estimée (horaire)</p>
      {vehicle.confidence === "APPROXIMATE" && (
        <p style={{ margin: "8px 0 0", padding: "6px 8px", background: "#fef3c7", borderRadius: 6, color: "#92400e" }}>
          Position approximative : le flux temps réel n'annonce qu'un seul arrêt pour ce train.
        </p>
      )}
      {vehicle.recordedAt && (
        <p style={{ margin: "4px 0", color: "#666" }}>
          Donnée du {new Date(vehicle.recordedAt).toLocaleTimeString("fr-FR")}
        </p>
      )}
      <button
        onClick={onFollow}
        style={{
          marginTop: 8,
          padding: "6px 12px",
          border: "1px solid #1d4ed8",
          borderRadius: 6,
          cursor: "pointer",
          background: following ? "#1d4ed8" : "#fff",
          color: following ? "#fff" : "#1d4ed8",
          font: "13px sans-serif",
        }}
      >
        {following ? "◉ Suivi actif" : "◉ Suivre"}
      </button>
    </div>
  );
}
