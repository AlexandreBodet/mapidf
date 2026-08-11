import styles from "./SheetFooter.module.css";

interface Props {
  /** Horodatage du dernier snapshot servi par `/vehicles` ; null avant le premier poll. */
  asOf: string | null;
}

/**
 * Pied de panneau commun aux deux mises en page, dédié à la mention de licence (art. 5.7 : nature
 * estimée + heure du snapshot). Il appartient au conteneur et non au contenu : en mode étroit, une
 * fiche remplace le sélecteur de lignes, et la mention disparaîtrait avec lui alors que les trains
 * restent affichés sur la carte.
 */
export function SheetFooter({ asOf }: Props) {
  return (
    <div className={styles.footer}>
      Position estimée (pas de GPS en métro). Les trains atténués ont un placement approximatif.
      {/* Date de mise à jour de la donnée : l'article 5.7 de la Licence Mobilité (« neutralité
          et loyauté ») interdit d'induire en erreur sur le contenu ET sur sa date de mise à
          jour. Le disclaimer ci-dessus couvre la nature estimée, cette ligne la fraîcheur. */}
      {asOf && ` Données IDFM du ${new Date(asOf).toLocaleTimeString("fr-FR")}.`}
    </div>
  );
}
