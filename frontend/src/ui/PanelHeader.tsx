interface Props {
  title: string;
  onClose: () => void;
}

/** Titre et fermeture d'une fiche, communs à la carte flottante et à la feuille. */
export function PanelHeader({ title, onClose }: Props) {
  return (
    <div style={{ display: "flex", alignItems: "flex-start", gap: 8, margin: "0 0 8px" }}>
      {/* Aucun style de police : le `<h3>` d'origine n'en avait pas non plus et héritait du
          défaut du navigateur (gras, 1.17em). En fixer un rétrécirait le titre. */}
      <h3 style={{ margin: 0, flex: 1, minWidth: 0 }}>{title}</h3>
      <button
        onClick={onClose}
        aria-label="Fermer"
        style={{
          flex: "0 0 auto",
          border: "none",
          background: "none",
          cursor: "pointer",
          fontSize: 20,
          lineHeight: 1,
          padding: 4,
          minWidth: "var(--tap)",
          minHeight: "var(--tap)",
        }}
      >
        ✕
      </button>
    </div>
  );
}
