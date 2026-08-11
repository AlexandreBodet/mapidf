import styles from "./PanelHeader.module.css";

interface Props {
  title: string;
  onClose: () => void;
}

/** Titre et fermeture d'une fiche, communs à la carte flottante et à la feuille. */
export function PanelHeader({ title, onClose }: Props) {
  return (
    <div className={styles.header}>
      {/* Aucun style de police : le `<h3>` d'origine n'en avait pas non plus et héritait du
          défaut du navigateur (gras, 1.17em). En fixer un rétrécirait le titre. */}
      <h3 className={styles.title}>{title}</h3>
      <button
        className={styles.close}
        onClick={onClose}
        aria-label="Fermer"
      >
        ✕
      </button>
    </div>
  );
}
