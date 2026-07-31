import {
  useEffect, useRef, useState,
  type MouseEvent as ReactMouseEvent,
  type PointerEvent as ReactPointerEvent,
  type ReactNode,
} from "react";
import { cranHeight, nextCran, PEEK_HEIGHT, snap, type Cran } from "./sheetCrans";

interface Props {
  cran: Cran;
  onCranChange: (cran: Cran) => void;
  viewportHeight: number;
  /** Toujours visible, même repliée : posé sous la poignée, hors de la zone qui défile. */
  summary: ReactNode;
  children: ReactNode;
  /** Posé sous la zone qui défile : visible à tous les crans, quel que soit le contenu. */
  footer: ReactNode;
  label: string;
  /** Hauteur réelle de l'aperçu, mesurée : `App` en dérive le padding de caméra. */
  onPeekHeight: (height: number) => void;
}

/**
 * Feuille repliable du rendu étroit. Coquille présentationnelle : elle ne sait rien de son
 * contenu, et `App` détient le cran (la carte en dérive son padding de caméra).
 */
export function Sheet({
  cran, onCranChange, viewportHeight, summary, children, footer, label, onPeekHeight,
}: Props) {
  const settled = cranHeight(cran, viewportHeight);
  // Non nul seulement pendant un glissement : sert aussi à couper la transition.
  const [dragged, setDragged] = useState<number | null>(null);
  const section = useRef<HTMLElement>(null);
  // Tout l'état du geste vit dans un ref, jamais dans `dragged` : les événements pointeur
  // arrivent plus vite que les rendus, et lire un état pas encore commité perdrait le premier
  // mouvement — ou ferait atterrir le lâcher sur une hauteur périmée.
  const gesture = useRef({
    active: false, startY: 0, startHeight: 0,
    lastY: 0, lastT: 0, velocity: 0, height: 0, moved: false,
  });
  const height = dragged ?? settled;
  // Replié, la feuille se dimensionne sur son contenu : les cibles tactiles de 44 px du résumé
  // (et l'alerte de gel) dépassent les 96 px du cran, et le surplus sortait par le bas de l'écran.
  const peeking = cran === "apercu" && dragged === null;

  useEffect(() => {
    const element = section.current;
    if (!element || !peeking) {
      return;
    }
    const observer = new ResizeObserver(() => onPeekHeight(element.getBoundingClientRect().height));
    observer.observe(element);
    return () => observer.disconnect();
  }, [peeking, onPeekHeight]);

  const onPointerDown = (event: ReactPointerEvent<HTMLButtonElement>) => {
    event.currentTarget.setPointerCapture(event.pointerId);
    // Hauteur mesurée quand l'aperçu se dimensionne sur son contenu : `cranHeight` ferait sauter
    // la feuille au premier pixel du glissement.
    const startHeight = peeking
      ? (section.current?.getBoundingClientRect().height ?? settled)
      : settled;
    gesture.current = {
      active: true, startY: event.clientY, startHeight,
      lastY: event.clientY, lastT: event.timeStamp, velocity: 0, height: startHeight, moved: false,
    };
    setDragged(startHeight);
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

  const onPointerUp = (event: ReactPointerEvent<HTMLButtonElement>) => {
    const g = gesture.current;
    if (!g.active) {
      return;
    }
    g.active = false;
    setDragged(null);
    // Un doigt immobile n'émet aucun pointermove : sans ça, la vitesse du dernier mouvement
    // survivrait à une pause et ferait repartir la feuille dans le mauvais sens.
    const velocity = event.timeStamp - g.lastT > 60 ? 0 : g.velocity;
    onCranChange(snap(g.height, velocity, viewportHeight));
  };

  // Un toucher sans déplacement, ou une touche Entrée/Espace sur la poignée : cran suivant.
  const onClick = (event: ReactMouseEvent<HTMLButtonElement>) => {
    // `detail === 0` = activation clavier, qui n'émet aucun événement pointeur : `moved` y
    // porterait encore la trace du dernier glissement et bloquerait la touche pour de bon.
    if (event.detail === 0 || !gesture.current.moved) {
      onCranChange(nextCran(cran));
    }
  };

  return (
    <section
      ref={section}
      aria-label={label}
      style={{
        position: "fixed",
        left: 0,
        right: 0,
        bottom: 0,
        // La zone sûre s'ajoute à la hauteur du cran : sinon elle la rognerait et l'aperçu
        // perdrait sa ligne de résumé sur les iPhone récents.
        height: peeking ? "auto" : `calc(${height}px + env(safe-area-inset-bottom, 0px))`,
        minHeight: peeking ? `calc(${PEEK_HEIGHT}px + env(safe-area-inset-bottom, 0px))` : undefined,
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
      <div
        style={{
          flex: "1 1 auto",
          overflowY: "auto",
          overscrollBehavior: "contain",
          padding: "0 12px 12px",
          // Masqué plutôt que démonté : l'état des composants survit, et les 16 pastilles sortent
          // de l'ordre de tabulation au lieu de rester focusables hors écran.
          display: peeking ? "none" : undefined,
        }}
      >
        {children}
      </div>
      <div style={{ flex: "0 0 auto", padding: "0 12px 12px" }}>{footer}</div>
    </section>
  );
}
