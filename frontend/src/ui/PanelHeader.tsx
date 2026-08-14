import styles from "./PanelHeader.module.css";

interface Props {
  title: string;
  onClose: () => void;
}

/** Titre et fermeture d'une fiche, communs à la carte flottante et à la feuille. */
export function PanelHeader({ title, onClose }: Props) {
  return (
    <div className={styles.header}>
      {/* `<h3>` devient `<h2>` : sous le `h1` masqué d'App, c'est le niveau juste (UX-4) — un geste
          sémantique, pas visuel. Le rendu ne bouge pas : `.title` repose explicitement le
          `font-size: 1.17em` par défaut d'un `<h3>`, pour que le passage de niveau ne se voie pas. */}
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
