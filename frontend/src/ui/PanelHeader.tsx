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
          défaut du navigateur. Le passage en `h2` change donc la taille rendue — c'est voulu, il
          n'y avait aucun `h1` au-dessus et le niveau 3 était orphelin. */}
      <h2 className={styles.title}>{title}</h2>
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
