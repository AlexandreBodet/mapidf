import {
  useEffect, useRef, useState,
  type CSSProperties,
  type MouseEvent as ReactMouseEvent,
  type PointerEvent as ReactPointerEvent,
  type ReactNode,
} from "react";
import { cranHeight, nextCran, PEEK_HEIGHT, snap, type Cran } from "./sheetCrans";
import styles from "./Sheet.module.css";

interface Props {
  cran: Cran;
  onCranChange: (cran: Cran) => void;
  viewportHeight: number;
  /**
   * Titre et fermeture d'une fiche ouverte, `null` sinon. Échappe au repli comme `alert` : au
   * cran `apercu`, on doit pouvoir refermer la fiche et savoir laquelle est ouverte.
   */
  header: ReactNode;
  /** Masqué au cran `apercu`, contrairement à `header` : résumé du réseau sans fiche ouverte. */
  summary: ReactNode;
  children: ReactNode;
  /** Posé sous la zone qui défile : visible à tous les crans, quel que soit le contenu. */
  footer: ReactNode;
  /**
   * Échappe au repli, contrairement à `summary`/`footer` : une panne de rafraîchissement ne doit
   * jamais devenir silencieuse au cran `apercu`. Rendu juste sous la poignée.
   */
  alert: ReactNode;
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
  cran, onCranChange, viewportHeight, header, summary, children, footer, label, onPeekHeight, asOf, alert,
}: Props) {
  const settled = cranHeight(cran, viewportHeight);
  // Non nul seulement pendant un glissement : sert aussi à couper la transition.
  const [dragged, setDragged] = useState<number | null>(null);
  // Hauteur réelle du cran apercu (poignée + alerte de gel + en-tête de fiche), mesurée sur
  // `peek` ci-dessous. Initialisée à la constante pour ne rien faire clignoter avant la première
  // mesure (cf. `peek`, effet juste en dessous).
  const [peekHeight, setPeekHeight] = useState(PEEK_HEIGHT);
  const peek = useRef<HTMLDivElement>(null);
  const content = useRef<HTMLDivElement>(null);
  // Tout l'état du geste vit dans un ref, jamais dans `dragged` : les événements pointeur
  // arrivent plus vite que les rendus, et lire un état pas encore commité perdrait le premier
  // mouvement — ou ferait atterrir le lâcher sur une hauteur périmée.
  // `bodyDeciding`/`bodyDeclined` n'existent que pour un geste démarré dans le corps : tant que
  // la décision (glisser la feuille ou défiler le contenu) n'est pas prise, on observe sans
  // intercepter ; une fois prise, elle vaut pour tout le reste du geste.
  const gesture = useRef({
    active: false, startY: 0, startHeight: 0, floor: 0,
    lastY: 0, lastT: 0, velocity: 0, height: 0, moved: false,
    bodyDeciding: false, bodyDeclined: false,
  });
  const height = dragged ?? settled;
  // Replié, la feuille se dimensionne sur la mesure de `peek` : au cran apercu, résumé, corps et
  // pied disparaissent (voir les `hidden` ci-dessous) — il ne reste que la poignée et les deux
  // exemptions qui échappent au repli : l'alerte de gel (une panne ne doit jamais être muette) et
  // l'en-tête de fiche (dit ce que contient la feuille).
  const peeking = cran === "apercu" && dragged === null;

  useEffect(() => {
    const element = peek.current;
    if (!element) {
      return;
    }
    // Observe `peek`, pas la `<section>` : fixer la hauteur de la section d'après sa propre
    // mesure boucherait (mesurer → fixer → mesurer). Actif en permanence, pas seulement au cran
    // apercu : `applyMove` a besoin d'un plancher à jour même quand la feuille est ouverte.
    const observer = new ResizeObserver(() => {
      const measured = element.getBoundingClientRect().height;
      setPeekHeight(measured);
      onPeekHeight(measured);
    });
    observer.observe(element);
    return () => observer.disconnect();
  }, [onPeekHeight]);

  // Démarre (ou reprend) un glissement de la feuille : partagé par la poignée, qui l'amorce dès
  // le pointerdown, et le corps, qui ne l'amorce qu'une fois le geste décidé (cf. plus bas).
  const beginDrag = (startY: number, timeStamp: number) => {
    const startHeight = peeking ? peekHeight : settled;
    gesture.current = {
      active: true, startY, startHeight, floor: peekHeight,
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
    // Plancher = hauteur mesurée de l'aperçu (poignée + zone sûre exclue, ajoutée séparément),
    // pas la constante `cranHeight("apercu", ...)` : sinon la feuille peut descendre sous sa
    // hauteur de repos réelle sur un appareil avec zone sûre (résiduel B).
    g.height = Math.max(
      g.floor,
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
      aria-label={label}
      className={styles.sheet}
      data-dragging={dragged !== null}
      // L'assertion est nécessaire : `CSSProperties` ne connaît pas les variables CSS et `tsc`
      // refuse la propriété (TS2353) sans elle.
      style={{ "--sheet-height": `${peeking ? peekHeight : height}px` } as CSSProperties}
    >
      <div ref={peek} className={styles.peek}>
        <button
          onPointerDown={onHandlePointerDown}
          onPointerMove={onHandlePointerMove}
          onPointerUp={onHandlePointerUp}
          onPointerCancel={onHandlePointerUp}
          onClick={onHandleClick}
          aria-expanded={cran !== "apercu"}
          aria-label="Changer la hauteur du panneau"
          className={styles.handle}
        >
          <div className={styles.grip} />
          {asOf && (
            // Fraîcheur de la donnée (art. 5.7) : ne peut pas rejoindre le « ⓘ », MapLibre fige son
            // texte à la construction du contrôle. Décoratif pour le lecteur d'écran : l'info vit
            // aussi dans SheetFooter (crans ouverts) et dans le « ⓘ » (nature de la donnée).
            <span aria-hidden="true" className={styles.asOf}>
              estimé {new Date(asOf).toLocaleTimeString("fr-FR", { hour: "2-digit", minute: "2-digit" })}
            </span>
          )}
        </button>
        {/* Pas de `hidden` ici, contrairement au résumé/pied voisins : une alerte de gel doit
            rester visible même au cran apercu (retour recette, correctif 1). */}
        <div className={styles.zone}>{alert}</div>
        {/* Idem : sans lui, une fiche ouverte devient muette (plus de titre, plus de « ✕ ») une
            fois la feuille repliée (résiduel C). */}
        <div className={styles.zone}>{header}</div>
      </div>
      <div className={styles.zone} hidden={peeking}>
        {summary}
      </div>
      <div
        ref={content}
        onPointerDown={onBodyPointerDown}
        onPointerMove={onBodyPointerMove}
        onPointerUp={onBodyPointerUp}
        onPointerCancel={onBodyPointerUp}
        className={styles.body}
        // Masqué plutôt que démonté : l'état des composants survit, et les 16 pastilles sortent
        // de l'ordre de tabulation au lieu de rester focusables hors écran.
        hidden={peeking}
      >
        {children}
      </div>
      <div className={styles.footer} hidden={peeking}>
        {footer}
      </div>
    </section>
  );
}
