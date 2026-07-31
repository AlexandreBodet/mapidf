interface Props {
  total: number;
  disruptedCount: number;
  disruptionsOpen: boolean;
  onToggleDisruptions: () => void;
  /** Un sous-ensemble de lignes est isolé : « tout afficher » a du sens. */
  canShowAll: boolean;
  onShowAll: () => void;
}

/**
 * Résumé de l'état du réseau. Sur écran large, ligne de tête du sélecteur ; sur écran étroit, le
 * seul contenu visible quand la feuille est repliée — d'où son extraction hors du `LinePicker`.
 */
export function NetworkSummary({
  total, disruptedCount, disruptionsOpen, onToggleDisruptions, canShowAll, onShowAll,
}: Props) {
  return (
    <>
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", gap: 8 }}>
        <b>{total} trains en circulation</b>
        {canShowAll && (
          <button
            onClick={onShowAll}
            style={{
              border: "none", background: "none", color: "#1d4ed8", cursor: "pointer",
              font: "inherit", minHeight: "var(--tap)",
            }}
          >
            tout afficher
          </button>
        )}
      </div>
      {disruptedCount > 0 && (
        <button
          onClick={onToggleDisruptions}
          style={{
            marginTop: 6, padding: 0, border: "none", background: "none", cursor: "pointer",
            font: "inherit", color: "#b45309", textAlign: "left", minHeight: "var(--tap)",
          }}
          aria-expanded={disruptionsOpen}
        >
          {disruptedCount === 1 ? "1 ligne perturbée" : `${disruptedCount} lignes perturbées`}
          {disruptionsOpen ? " ▾" : " ▸"}
        </button>
      )}
    </>
  );
}
