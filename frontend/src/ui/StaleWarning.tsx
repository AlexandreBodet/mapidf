interface Props {
  /** Dernier poll `/vehicles` en échec : ce qui est affiché ne bouge plus. */
  stale: boolean;
}

/**
 * Séparée du pied parce qu'elle échappe au repli de la feuille : le cran `apercu` masque la
 * mention de licence, qui reste accessible par le « ⓘ », mais une panne de rafraîchissement ne
 * doit jamais être silencieuse.
 */
export function StaleWarning({ stale }: Props) {
  if (!stale) {
    return null;
  }
  return (
    <div style={{ color: "#b45309", marginTop: 6 }} role="status">
      ⚠ Positions plus mises à jour — la connexion au service est interrompue.
    </div>
  );
}
