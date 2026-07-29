import { formatEta } from "./formatEta";
import type { Vehicle } from "../api/types";

interface Props {
  // `VehicleSummary` a disparu avec le typage mono-ligne : `Vehicle` (types.ts, tâche 13)
  // couvre les mêmes champs affichés ici. Le tri/atténuation par confiance reste tâche 14.
  vehicle: Vehicle | null;
  following?: boolean;
  onFollow?: () => void;
  onClose: () => void;
}

const STATUS_LABELS: Record<string, string> = {
  ON_TIME: "à l'heure",
  ONTIME: "à l'heure",
  DELAYED: "retardé",
  EARLY: "en avance",
  CANCELLED: "supprimé",
  CANCELED: "supprimé",
};

export function VehiclePanel({ vehicle, following = false, onFollow, onClose }: Props) {
  if (!vehicle) {
    return null;
  }
  const statusLabel = STATUS_LABELS[vehicle.status?.toUpperCase()] ?? "—";
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
      <p style={{ margin: "4px 0" }}>État : {statusLabel}</p>
      <p style={{ margin: "4px 0", color: "#666" }}>
        Position : {vehicle.source === "REALTIME" ? "temps réel" : "estimée (horaire)"}
      </p>
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
