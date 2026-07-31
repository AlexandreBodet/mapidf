interface Props {
  /** Dernier poll `/vehicles` en échec : ce qui est affiché ne bouge plus. */
  stale: boolean;
  /** Horodatage du dernier snapshot servi par `/vehicles` ; null avant le premier poll. */
  asOf: string | null;
}

/**
 * Bas de panneau commun aux deux mises en page. Il appartient au conteneur et non au contenu :
 * en mode étroit, une fiche remplace le sélecteur de lignes, et la mention de l'art. 5.7
 * disparaîtrait avec lui alors que les trains restent affichés sur la carte.
 */
export function SheetFooter({ stale, asOf }: Props) {
  return (
    <>
      {stale && (
        <div style={{ color: "#b45309", marginTop: 6 }} role="status">
          ⚠ Positions plus mises à jour — la connexion au service est interrompue.
        </div>
      )}
      <div style={{ color: "#666", marginTop: 6 }}>
        Position estimée (pas de GPS en métro). Les trains atténués ont un placement approximatif.
        {/* Date de mise à jour de la donnée : l'article 5.7 de la Licence Mobilité (« neutralité
            et loyauté ») interdit d'induire en erreur sur le contenu ET sur sa date de mise à
            jour. Le disclaimer ci-dessus couvre la nature estimée, cette ligne la fraîcheur. */}
        {asOf && ` Données IDFM du ${new Date(asOf).toLocaleTimeString("fr-FR")}.`}
      </div>
    </>
  );
}
