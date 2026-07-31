interface Props {
  total: number;
  /** Hors des heures de service, une carte sans train est normale : le compteur mentirait. */
  inService: boolean;
  disruptedCount: number;
  disruptionsOpen: boolean;
  onToggleDisruptions: () => void;
  /** Un sous-ensemble de lignes est isolé : « tout afficher » a du sens. */
  canShowAll: boolean;
  onShowAll: () => void;
  /** Chevron de repli : seule la branche large l'expose, la feuille étroite a déjà ses crans. */
  collapsible?: boolean;
  expanded?: boolean;
  onToggleExpanded?: () => void;
}

/**
 * Résumé de l'état du réseau. Sur écran large, ligne de tête du sélecteur ; sur écran étroit, le
 * seul contenu visible quand la feuille est repliée — d'où son extraction hors du `LinePicker`.
 */
export function NetworkSummary({
  total, inService, disruptedCount, disruptionsOpen, onToggleDisruptions, canShowAll, onShowAll,
  collapsible, expanded, onToggleExpanded,
}: Props) {
  return (
    <>
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", gap: 8 }}>
        <b>{inService ? `${total} trains en circulation` : "Service terminé"}</b>
        <div style={{ display: "flex", alignItems: "center", gap: 4 }}>
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
          {collapsible && (
            <button
              onClick={onToggleExpanded}
              aria-expanded={expanded}
              aria-label={expanded ? "Replier le sélecteur de lignes" : "Déplier le sélecteur de lignes"}
              style={{
                border: "none", background: "none", color: "#666", cursor: "pointer",
                font: "inherit", minHeight: "var(--tap)", padding: 0,
              }}
            >
              {expanded ? "▾" : "▸"}
            </button>
          )}
        </div>
      </div>
      {/* Sans cette phrase, une carte vide se lit comme une panne — c'est le seul moment où le
          silence de l'appli et son échec se ressemblent. Aucune heure citée : le premier métro
          dépend de la ligne, et la fenêtre de service vit côté serveur. */}
      {!inService && (
        <div style={{ color: "#666", marginTop: 2 }}>Reprise au premier métro.</div>
      )}
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
