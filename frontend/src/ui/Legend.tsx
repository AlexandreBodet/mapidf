interface Props {
  color: string;
  count: number;
}

export function Legend({ color, count }: Props) {
  return (
    <div
      style={{
        position: "absolute",
        bottom: 12,
        left: 12,
        padding: "10px 12px",
        background: "#fff",
        borderRadius: 8,
        boxShadow: "0 2px 12px rgba(0,0,0,.2)",
        font: "13px sans-serif",
      }}
    >
      <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
        <span style={{ width: 12, height: 12, borderRadius: "50%", background: color, border: "2px solid #fff", boxShadow: "0 0 0 1px #ccc" }} />
        <b>{count} trains en circulation</b>
      </div>
      <div style={{ color: "#666", marginTop: 4 }}>Position estimée (pas de GPS en métro).</div>
    </div>
  );
}
