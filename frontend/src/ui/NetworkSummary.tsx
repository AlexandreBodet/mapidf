import styles from "./NetworkSummary.module.css";

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
      <div className={styles.head}>
        <b>{inService ? `${total} trains en circulation` : "Service terminé"}</b>
        <div className={styles.actions}>
          {canShowAll && (
            <button onClick={onShowAll} className={styles.showAll}>
              tout afficher
            </button>
          )}
          {collapsible && (
            <button
              onClick={onToggleExpanded}
              aria-expanded={expanded}
              aria-label={expanded ? "Replier le sélecteur de lignes" : "Déplier le sélecteur de lignes"}
              className={styles.chevron}
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
        <div className={styles.resume}>Reprise au premier métro.</div>
      )}
      {disruptedCount > 0 && (
        <button
          onClick={onToggleDisruptions}
          className={styles.disrupted}
          aria-expanded={disruptionsOpen}
        >
          {disruptedCount === 1 ? "1 ligne perturbée" : `${disruptedCount} lignes perturbées`}
          {disruptionsOpen ? " ▾" : " ▸"}
        </button>
      )}
    </>
  );
}
