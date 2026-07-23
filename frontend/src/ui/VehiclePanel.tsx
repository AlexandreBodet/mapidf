interface Props {
  vehicle: { headsign: string; nextStop: string; status: string; source: string } | null;
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

export function VehiclePanel({ vehicle, onClose }: Props) {
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
      <button onClick={onClose} style={{ float: "right", border: "none", background: "none", cursor: "pointer" }}>
        ✕
      </button>
      <h3 style={{ margin: "0 0 8px" }}>→ {vehicle.headsign}</h3>
      <p style={{ margin: "4px 0" }}>
        Prochain arrêt : <b>{vehicle.nextStop}</b>
      </p>
      <p style={{ margin: "4px 0" }}>État : {statusLabel}</p>
      <p style={{ margin: "4px 0", color: "#666" }}>
        Position : {vehicle.source === "REALTIME" ? "GPS temps réel" : "estimée (horaire)"}
      </p>
    </div>
  );
}
