import { useRef, useState, type PointerEvent as ReactPointerEvent, type ReactNode } from "react";
import { cranHeight, nextCran, snap, type Cran } from "./sheetCrans";

interface Props {
  cran: Cran;
  onCranChange: (cran: Cran) => void;
  viewportHeight: number;
  /** Toujours visible, même repliée : posé sous la poignée, hors de la zone qui défile. */
  summary: ReactNode;
  children: ReactNode;
  label: string;
}

/**
 * Feuille repliable du rendu étroit. Coquille présentationnelle : elle ne sait rien de son
 * contenu, et `App` détient le cran (la carte en dérive son padding de caméra).
 */
export function Sheet({ cran, onCranChange, viewportHeight, summary, children, label }: Props) {
  const settled = cranHeight(cran, viewportHeight);
  // Non nul seulement pendant un glissement : sert aussi à couper la transition.
  const [dragged, setDragged] = useState<number | null>(null);
  // Tout l'état du geste vit dans un ref, jamais dans `dragged` : les événements pointeur
  // arrivent plus vite que les rendus, et lire un état pas encore commité perdrait le premier
  // mouvement — ou ferait atterrir le lâcher sur une hauteur périmée.
  const gesture = useRef({
    active: false, startY: 0, startHeight: 0,
    lastY: 0, lastT: 0, velocity: 0, height: 0, moved: false,
  });
  const height = dragged ?? settled;

  const onPointerDown = (event: ReactPointerEvent<HTMLButtonElement>) => {
    event.currentTarget.setPointerCapture(event.pointerId);
    gesture.current = {
      active: true, startY: event.clientY, startHeight: settled,
      lastY: event.clientY, lastT: event.timeStamp, velocity: 0, height: settled, moved: false,
    };
    setDragged(settled);
  };

  const onPointerMove = (event: ReactPointerEvent<HTMLButtonElement>) => {
    const g = gesture.current;
    if (!g.active) {
      return;
    }
    const elapsed = event.timeStamp - g.lastT;
    if (elapsed > 0) {
      // Positif = la feuille grandit (doigt vers le haut), convention de `snap`.
      g.velocity = (g.lastY - event.clientY) / elapsed;
      g.lastY = event.clientY;
      g.lastT = event.timeStamp;
    }
    if (Math.abs(event.clientY - g.startY) > 6) {
      g.moved = true;
    }
    g.height = Math.max(
      cranHeight("apercu", viewportHeight),
      Math.min(cranHeight("plein", viewportHeight), g.startHeight + (g.startY - event.clientY)),
    );
    setDragged(g.height);
  };

  const onPointerUp = () => {
    const g = gesture.current;
    if (!g.active) {
      return;
    }
    g.active = false;
    setDragged(null);
    onCranChange(snap(g.height, g.velocity, viewportHeight, cran));
  };

  // Un toucher sans déplacement, ou une touche Entrée/Espace sur la poignée : cran suivant.
  const onClick = () => {
    if (!gesture.current.moved) {
      onCranChange(nextCran(cran));
    }
  };

  return (
    <section
      aria-label={label}
      style={{
        position: "fixed",
        left: 0,
        right: 0,
        bottom: 0,
        // La zone sûre s'ajoute à la hauteur du cran : sinon elle la rognerait et l'aperçu
        // perdrait sa ligne de résumé sur les iPhone récents.
        height: `calc(${height}px + env(safe-area-inset-bottom, 0px))`,
        paddingBottom: "env(safe-area-inset-bottom, 0px)",
        boxSizing: "border-box",
        display: "flex",
        flexDirection: "column",
        background: "#fff",
        borderRadius: "14px 14px 0 0",
        boxShadow: "0 -2px 16px rgba(0,0,0,.2)",
        font: "13px sans-serif",
        transition: dragged === null ? "height 220ms ease-out" : "none",
      }}
    >
      <button
        onPointerDown={onPointerDown}
        onPointerMove={onPointerMove}
        onPointerUp={onPointerUp}
        onPointerCancel={onPointerUp}
        onClick={onClick}
        aria-expanded={cran !== "apercu"}
        aria-label="Changer la hauteur du panneau"
        style={{
          flex: "0 0 auto",
          height: 44,
          border: "none",
          background: "none",
          padding: 0,
          cursor: "grab",
          // Sans ça, le navigateur traite le glissement vertical comme un défilement de page.
          touchAction: "none",
        }}
      >
        <div style={{ width: 36, height: 4, borderRadius: 2, background: "#ccc", margin: "0 auto" }} />
      </button>
      <div style={{ flex: "0 0 auto", padding: "0 12px" }}>{summary}</div>
      <div style={{ flex: "1 1 auto", overflowY: "auto", overscrollBehavior: "contain", padding: "0 12px 12px" }}>
        {children}
      </div>
    </section>
  );
}
