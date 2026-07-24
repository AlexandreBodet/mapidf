interface Props {
  vehicle: { headsign: string; nextStop: string; status: string; source: string; expectedTime: string } | null;
  following?: boolean;
  onFollow?: () => void;
  onClose: () => void;
}

function formatEta(expectedTime: string): string {
  const sec = Math.round((new Date(expectedTime).getTime() - Date.now()) / 1000);
  if (Number.isNaN(sec)) {
    return "—";
  }
  if (sec <= 0) {
    return "imminent / à quai";
  }
  if (sec < 60) {
    return `dans ${sec} s`;
  }
  return `dans ${Math.floor(sec / 60)} min ${sec % 60} s`;
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
      <button onClick={onClose} style={{ float: "right", border: "none", background: "none", cursor: "pointer" }}>
        ✕
      </button>
      <h3 style={{ margin: "0 0 8px" }}>→ {vehicle.headsign}</h3>
      <p style={{ margin: "4px 0" }}>
        Prochain arrêt : <b>{vehicle.nextStop}</b>
      </p>
      <p style={{ margin: "4px 0" }}>
        Arrivée estimée : <b>{formatEta(vehicle.expectedTime)}</b>
      </p>
      <p style={{ margin: "4px 0" }}>État : {statusLabel}</p>
      <p style={{ margin: "4px 0", color: "#666" }}>
        Position : {vehicle.source === "REALTIME" ? "GPS temps réel" : "estimée (horaire)"}
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
