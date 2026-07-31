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
  /** Horodatage du dernier snapshot ; affiché sur la poignée. null avant le premier poll. */
  asOf: string | null;
}

// Seuil de mouvement (px) en-deçà duquel un geste est encore ambigu : un toucher sans
// déplacement doit rester un clic, pas un glissement raté.
const MOVE_THRESHOLD = 6;

/**
 * Feuille repliable du rendu étroit. Coquille présentationnelle : elle ne sait rien de son
 * contenu, et `App` détient le cran (la carte en dérive son padding de caméra).
 */
export function Sheet({
  cran, onCranChange, viewportHeight, summary, children, footer, label, onPeekHeight, asOf,
}: Props) {
  const settled = cranHeight(cran, viewportHeight);
  // Non nul seulement pendant un glissement : sert aussi à couper la transition.
  const [dragged, setDragged] = useState<number | null>(null);
  const section = useRef<HTMLElement>(null);
  const content = useRef<HTMLDivElement>(null);
  // Tout l'état du geste vit dans un ref, jamais dans `dragged` : les événements pointeur
  // arrivent plus vite que les rendus, et lire un état pas encore commité perdrait le premier
  // mouvement — ou ferait atterrir le lâcher sur une hauteur périmée.
  // `bodyDeciding`/`bodyDeclined` n'existent que pour un geste démarré dans le corps : tant que
  // la décision (glisser la feuille ou défiler le contenu) n'est pas prise, on observe sans
  // intercepter ; une fois prise, elle vaut pour tout le reste du geste.
  const gesture = useRef({
    active: false, startY: 0, startHeight: 0,
    lastY: 0, lastT: 0, velocity: 0, height: 0, moved: false,
    bodyDeciding: false, bodyDeclined: false,
  });
  const height = dragged ?? settled;
  // Replié, la feuille se dimensionne sur son contenu : au cran apercu, résumé et pied
  // disparaissent (retour recette — voir les deux `display: peeking ? "none" : undefined`
  // ci-dessous), donc seule la poignée reste.
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

  // Démarre (ou reprend) un glissement de la feuille : partagé par la poignée, qui l'amorce dès
  // le pointerdown, et le corps, qui ne l'amorce qu'une fois le geste décidé (cf. plus bas).
  const beginDrag = (startY: number, timeStamp: number) => {
    const startHeight = peeking
      ? (section.current?.getBoundingClientRect().height ?? settled)
      : settled;
    gesture.current = {
      active: true, startY, startHeight,
      lastY: startY, lastT: timeStamp, velocity: 0, height: startHeight, moved: false,
      bodyDeciding: false, bodyDeclined: false,
    };
    setDragged(startHeight);
  };

  const applyMove = (clientY: number, timeStamp: number) => {
    const g = gesture.current;
    const elapsed = timeStamp - g.lastT;
    if (elapsed > 0) {
      // Positif = la feuille grandit (doigt vers le haut), convention de `snap`.
      g.velocity = (g.lastY - clientY) / elapsed;
      g.lastY = clientY;
      g.lastT = timeStamp;
    }
    if (Math.abs(clientY - g.startY) > MOVE_THRESHOLD) {
      g.moved = true;
    }
    g.height = Math.max(
      cranHeight("apercu", viewportHeight),
      Math.min(cranHeight("plein", viewportHeight), g.startHeight + (g.startY - clientY)),
    );
    setDragged(g.height);
  };

  const endDrag = (timeStamp: number) => {
    const g = gesture.current;
    g.active = false;
    setDragged(null);
    // Un doigt immobile n'émet aucun pointermove : sans ça, la vitesse du dernier mouvement
    // survivrait à une pause et ferait repartir la feuille dans le mauvais sens.
    const velocity = timeStamp - g.lastT > 60 ? 0 : g.velocity;
    onCranChange(snap(g.height, velocity, viewportHeight));
  };

  const onHandlePointerDown = (event: ReactPointerEvent<HTMLButtonElement>) => {
    event.currentTarget.setPointerCapture(event.pointerId);
    beginDrag(event.clientY, event.timeStamp);
  };

  const onHandlePointerMove = (event: ReactPointerEvent<HTMLButtonElement>) => {
    if (!gesture.current.active) {
      return;
    }
    applyMove(event.clientY, event.timeStamp);
  };

  const onHandlePointerUp = (event: ReactPointerEvent<HTMLButtonElement>) => {
    if (!gesture.current.active) {
      return;
    }
    endDrag(event.timeStamp);
  };

  // Un toucher sans déplacement, ou une touche Entrée/Espace sur la poignée : cran suivant.
  // L'affaire de la poignée seule — le corps n'a pas de gestionnaire onClick (retour recette).
  const onHandleClick = (event: ReactMouseEvent<HTMLButtonElement>) => {
    // `detail === 0` = activation clavier, qui n'émet aucun événement pointeur : `moved` y
    // porterait encore la trace du dernier glissement et bloquerait la touche pour de bon.
    if (event.detail === 0 || !gesture.current.moved) {
      onCranChange(nextCran(cran));
    }
  };

  // Un glissement démarré dans le corps ne prend la main que s'il part du haut du défilement et
  // va vers le bas : c'est le seul cas où le navigateur n'a rien à défiler, donc aucun conflit à
  // arbitrer avec le défilement natif (cf. commentaire de tête, retour recette #3).
  const onBodyPointerDown = (event: ReactPointerEvent<HTMLDivElement>) => {
    const g = gesture.current;
    g.bodyDeciding = true;
    g.bodyDeclined = false;
    g.startY = event.clientY;
    g.lastY = event.clientY;
    g.lastT = event.timeStamp;
  };

  const onBodyPointerMove = (event: ReactPointerEvent<HTMLDivElement>) => {
    const g = gesture.current;
    if (g.active) {
      // Le corps a déjà pris la main plus tôt dans ce geste : suite d'un glissement normal.
      applyMove(event.clientY, event.timeStamp);
      return;
    }
    if (!g.bodyDeciding || g.bodyDeclined) {
      return;
    }
    if (Math.abs(event.clientY - g.startY) <= MOVE_THRESHOLD) {
      // Pas encore décidé : on garde trace du dernier point pour que la vitesse du futur
      // glissement (si la décision tombe de ce côté) ne compte pas ce délai d'attente.
      g.lastY = event.clientY;
      g.lastT = event.timeStamp;
      return;
    }
    g.bodyDeciding = false;
    const goingDown = event.clientY > g.startY;
    const atTop = (content.current?.scrollTop ?? 0) <= 0;
    if (goingDown && atTop) {
      event.currentTarget.setPointerCapture(event.pointerId);
      beginDrag(g.startY, g.lastT);
      applyMove(event.clientY, event.timeStamp);
    } else {
      // Défilement : on ne retente plus la décision jusqu'au pointerup de ce geste.
      g.bodyDeclined = true;
    }
  };

  const onBodyPointerUp = (event: ReactPointerEvent<HTMLDivElement>) => {
    const g = gesture.current;
    if (g.active) {
      endDrag(event.timeStamp);
    }
    g.bodyDeciding = false;
    g.bodyDeclined = false;
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
        onPointerDown={onHandlePointerDown}
        onPointerMove={onHandlePointerMove}
        onPointerUp={onHandlePointerUp}
        onPointerCancel={onHandlePointerUp}
        onClick={onHandleClick}
        aria-expanded={cran !== "apercu"}
        aria-label="Changer la hauteur du panneau"
        style={{
          flex: "0 0 auto",
          height: 44,
          position: "relative",
          border: "none",
          background: "none",
          padding: 0,
          cursor: "grab",
          // Sans ça, le navigateur traite le glissement vertical comme un défilement de page.
          touchAction: "none",
        }}
      >
        <div style={{ width: 36, height: 4, borderRadius: 2, background: "#ccc", margin: "0 auto" }} />
        {asOf && (
          // Fraîcheur de la donnée (art. 5.7) : ne peut pas rejoindre le « ⓘ », MapLibre fige son
          // texte à la construction du contrôle. Décoratif pour le lecteur d'écran : l'info vit
          // aussi dans SheetFooter (crans ouverts) et dans le « ⓘ » (nature de la donnée).
          <span
            aria-hidden="true"
            style={{
              position: "absolute", right: 12, top: "50%", transform: "translateY(-50%)",
              font: "10px sans-serif", color: "#999",
            }}
          >
            estimé {new Date(asOf).toLocaleTimeString("fr-FR", { hour: "2-digit", minute: "2-digit" })}
          </span>
        )}
      </button>
      <div style={{ flex: "0 0 auto", padding: "0 12px", display: peeking ? "none" : undefined }}>
        {summary}
      </div>
      <div
        ref={content}
        onPointerDown={onBodyPointerDown}
        onPointerMove={onBodyPointerMove}
        onPointerUp={onBodyPointerUp}
        onPointerCancel={onBodyPointerUp}
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
      <div style={{ flex: "0 0 auto", padding: "0 12px 12px", display: peeking ? "none" : undefined }}>
        {footer}
      </div>
    </section>
  );
}
