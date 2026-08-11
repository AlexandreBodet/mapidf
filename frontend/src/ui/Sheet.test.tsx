// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { stubHeight, triggerResize } from "../test/setup";
import { Sheet } from "./Sheet";
import type { Cran } from "./sheetCrans";

// Sans `globals: true`, le nettoyage automatique de Testing Library ne s'enregistre pas.
afterEach(cleanup);

const VIEWPORT = 844; // iPhone 12/13 en portrait, la cible du chantier UX-2.

/**
 * jsdom 26 n'expose pas de constructeur global `PointerEvent` : `fireEvent.pointerDown/Move/Up`
 * retombe alors sur `Event` nu (cf. `@testing-library/dom`, `createEvent`, qui utilise
 * `window[EventType] || window.Event`) et perd `clientY`/`pointerId` en route — le geste atteint
 * bien le gestionnaire de `Sheet`, mais avec des coordonnées `undefined`, donc des hauteurs
 * calculées en `NaN`. On construit donc l'événement à la main sur `MouseEvent`, lui correctement
 * supporté par jsdom, et on pose `pointerId` en propriété brute — elle ne sert qu'à
 * `setPointerCapture`, stubbé en no-op par `src/test/setup.ts`, donc sa valeur est inerte ici.
 * `timeStamp` est un paramètre (et non une constante) : React calcule
 * `event.timeStamp || Date.now()` (cf. `react-dom`), donc `0` serait ignoré et remplacé par
 * l'heure réelle — mais une valeur non nulle, choisie par l'appelant, rend `elapsed` (et donc la
 * vitesse dans `applyMove`/`endDrag`) entièrement déterministe. La vitesse **est** donc testable
 * ici : la valeur par défaut (identique sur tout un geste) l'annule pour les tests qui n'en ont
 * pas besoin ; les tests de coup sec ci-dessous l'écartent volontairement.
 */
function firePointer(
  element: Element,
  type: "pointerdown" | "pointermove" | "pointerup",
  clientY: number,
  timeStamp = 1,
) {
  const event = new MouseEvent(type, { bubbles: true, cancelable: true, clientY });
  Object.defineProperty(event, "pointerId", { value: 1 });
  Object.defineProperty(event, "timeStamp", { value: timeStamp });
  fireEvent(element, event);
}

// `timeStamp` identique (défaut) sur les trois événements : `elapsed` reste à 0 dans `applyMove`,
// donc la vitesse aussi — un glissement « lent », sans coup sec possible.
function drag(element: Element, fromY: number, toY: number) {
  firePointer(element, "pointerdown", fromY);
  firePointer(element, "pointermove", toY);
  firePointer(element, "pointerup", toY);
}

function renderSheet(cran: Cran, asOf: string | null = null) {
  const onCranChange = vi.fn();
  const onPeekHeight = vi.fn();
  render(
    <Sheet
      cran={cran}
      onCranChange={onCranChange}
      viewportHeight={VIEWPORT}
      header={<p>titre fiche</p>}
      summary={<p>résumé</p>}
      footer={<p>pied</p>}
      alert={<p>alerte gel</p>}
      label="État du réseau"
      onPeekHeight={onPeekHeight}
      asOf={asOf}
    >
      <p>corps</p>
    </Sheet>,
  );
  const handle = screen.getByRole("button", { name: "Changer la hauteur du panneau" });
  return { onCranChange, onPeekHeight, handle, body: screen.getByText("corps").parentElement! };
}

// `getByText` ne voit que la présence dans le DOM, pas `display: none` posé sur un ancêtre : on
// remonte la chaîne jusqu'au `body` pour savoir si l'élément est réellement masqué.
function isHidden(element: Element): boolean {
  let el: Element | null = element;
  while (el && el !== document.body) {
    if (el instanceof HTMLElement && el.style.display === "none") {
      return true;
    }
    el = el.parentElement;
  }
  return false;
}

describe("Sheet — poignée", () => {
  it("change de cran quand on la tire vers le haut", () => {
    const { onCranChange, handle } = renderSheet("moitie");

    drag(handle, 400, 100);

    // 422 + 300 = 722, plus proche de 760 (plein) que de 422 (moitié).
    expect(onCranChange).toHaveBeenCalledWith("plein");
  });

  it("répond encore au clavier après un glissement", () => {
    // Le défaut exact d'UX-2 : `moved` n'était jamais remis à zéro, donc la poignée devenait
    // inerte au clavier dès le premier glissement. La correction lit `event.detail === 0`.
    const { onCranChange, handle } = renderSheet("apercu");

    drag(handle, 400, 300);
    onCranChange.mockClear();

    // `detail: 0` = activation clavier (Entrée/Espace), qui n'émet aucun événement pointeur.
    fireEvent.click(handle, { detail: 0 });

    expect(onCranChange).toHaveBeenCalledWith("moitie");
  });
});

describe("Sheet — poignée : vitesse d'un coup sec", () => {
  it("un coup sec vers le haut avance d'un cran de plus que le plus proche", () => {
    const { onCranChange, handle } = renderSheet("apercu");

    firePointer(handle, "pointerdown", 400, 100);
    firePointer(handle, "pointermove", 390, 105);
    firePointer(handle, "pointerup", 390, 106);

    // 44 + (400-390) = 54, plus proche de l'aperçu (44) que de moitié (422) : sans vitesse, le
    // cran resterait "apercu". Mais (400-390)/(105-100) = 2 px/ms, bien au-delà de FLICK (0.5,
    // cf. sheetCrans.ts) : le coup sec avance d'un cran de plus que le plus proche.
    expect(onCranChange).toHaveBeenCalledWith("moitie");
  });

  it("un coup sec vers le bas recule d'un cran de plus que le plus proche", () => {
    const { onCranChange, handle } = renderSheet("plein");

    firePointer(handle, "pointerdown", 100, 100);
    firePointer(handle, "pointermove", 110, 105);
    firePointer(handle, "pointerup", 110, 106);

    // 760 + (100-110) = 750, plus proche du plein (760) que de moitié (422) : sans vitesse, le
    // cran resterait "plein". Mais (100-110)/(105-100) = -2 px/ms, sous -FLICK (-0.5) : le coup
    // sec recule d'un cran de plus que le plus proche.
    expect(onCranChange).toHaveBeenCalledWith("moitie");
  });

  it("un dernier mouvement suivi d'une pause de plus de 60 ms retombe sur le cran le plus proche", () => {
    const { onCranChange, handle } = renderSheet("plein");

    firePointer(handle, "pointerdown", 100, 100);
    firePointer(handle, "pointermove", 200, 105);
    firePointer(handle, "pointerup", 200, 181);

    // 760 + (100-200) = 660, plus proche du plein (760, distance 100) que de moitié (422,
    // distance 238). Sans le lâcher retardé, (100-200)/(105-100) = -20 px/ms aurait déclenché un
    // coup sec vers le bas (recul à "moitie"). Mais le lâcher arrive 76 ms après le dernier
    // mouvement (> 60 ms) : la garde de Sheet.tsx remet la vitesse à 0, et c'est le cran le plus
    // proche, "plein", qui l'emporte.
    expect(onCranChange).toHaveBeenCalledWith("plein");
  });
});

describe("Sheet — poignée : clic du navigateur après un geste", () => {
  it("un tap immobile puis le clic natif du navigateur avancent d'un cran", () => {
    const { onCranChange, handle } = renderSheet("apercu");

    firePointer(handle, "pointerdown", 400);
    firePointer(handle, "pointerup", 400);
    onCranChange.mockClear();

    // `detail: 1` = clic natif (souris/tactile), qui suit tout pointerup côté navigateur réel —
    // aucun test précédent ne l'envoyait. Sans déplacement, `moved` est resté `false` : la
    // clause `!gesture.current.moved` de Sheet.tsx doit laisser passer.
    fireEvent.click(handle, { detail: 1 });

    expect(onCranChange).toHaveBeenCalledWith("moitie"); // nextCran("apercu")
  });

  it("le clic natif après un glissement n'avance pas d'un cran supplémentaire", () => {
    const { onCranChange, handle } = renderSheet("moitie");

    drag(handle, 400, 100); // atterrit sur "plein", comme le premier test de ce fichier.
    onCranChange.mockClear();

    // Même clic natif que ci-dessus, mais après un glissement réel : `moved` vaut `true`, donc
    // Sheet.tsx doit l'ignorer — sinon chaque glissement avancerait d'un cran de trop.
    fireEvent.click(handle, { detail: 1 });

    expect(onCranChange).not.toHaveBeenCalled();
  });
});

describe("Sheet — corps", () => {
  it("laisse le défilement gagner quand le contenu n'est pas remonté en haut", () => {
    const { onCranChange, body } = renderSheet("plein");
    Object.defineProperty(body, "scrollTop", { value: 50, configurable: true });

    drag(body, 100, 500);

    expect(onCranChange).not.toHaveBeenCalled();
  });

  it("replie la feuille jusqu'au cran le plus proche pour un glissement lent (sans vitesse)", () => {
    const { onCranChange, body } = renderSheet("plein");
    Object.defineProperty(body, "scrollTop", { value: 0, configurable: true });

    // `drag` pose le même `timeStamp` sur les trois événements : `elapsed` reste à 0, la
    // vitesse aussi. Sans ça, un glissement de cette amplitude est un coup sec (cf. test
    // suivant) et retombe un cran plus loin.
    drag(body, 100, 500);

    // 760 - 400 = 360, plus proche de 422 (moitié) que de 44 (aperçu).
    expect(onCranChange).toHaveBeenCalledWith("moitie");
  });

  it("un coup sec vers le bas depuis un contenu déjà en haut replie jusqu'à l'aperçu", () => {
    const { onCranChange, body } = renderSheet("plein");
    Object.defineProperty(body, "scrollTop", { value: 0, configurable: true });

    firePointer(body, "pointerdown", 100, 10);
    firePointer(body, "pointermove", 500, 15);
    firePointer(body, "pointerup", 500, 16);

    // Même amplitude que le glissement lent ci-dessus (760 - 400 = 360, plus proche de 422 que
    // 44), mais (100-500)/(15-10) = -80 px/ms, bien sous -FLICK (-0.5) : le coup sec pousse un
    // cran plus loin que le plus proche, jusqu'à l'aperçu.
    expect(onCranChange).toHaveBeenCalledWith("apercu");
  });
});

describe("Sheet — repli au cran apercu", () => {
  it("garde l'alerte de gel et l'en-tête de fiche visibles, masque le résumé et le corps", () => {
    // Les deux exemptions au repli (retour recette) : sans elles, une panne de rafraîchissement
    // ou une fiche ouverte deviendraient muettes une fois la feuille repliée à l'aperçu.
    renderSheet("apercu");

    expect(isHidden(screen.getByText("alerte gel"))).toBe(false);
    expect(isHidden(screen.getByText("titre fiche"))).toBe(false);
    expect(isHidden(screen.getByText("résumé"))).toBe(true);
    expect(isHidden(screen.getByText("corps"))).toBe(true);
  });
});

describe("Sheet — mesure de l'aperçu", () => {
  it("remonte la hauteur mesurée, dont App dérive le padding de caméra", () => {
    const { onPeekHeight, handle } = renderSheet("apercu");
    stubHeight(handle.parentElement!, 96);

    triggerResize();

    expect(onPeekHeight).toHaveBeenCalledWith(96);
  });
});

describe("Sheet — fraîcheur sur la poignée", () => {
  it("affiche l'heure de l'instantané, décorative pour le lecteur d'écran", () => {
    // Sous 720 px, la feuille se replie jusqu'à sa poignée : c'est le seul endroit où la date de
    // la donnée reste lisible (art. 5.7). Le texte de l'attribution MapLibre ne peut pas la
    // porter — il se fige à la construction du contrôle.
    const { handle } = renderSheet("apercu", "2026-08-11T14:32:10Z");

    const heure = handle.querySelector('[aria-hidden="true"]');
    expect(heure).not.toBeNull();
    expect(heure!.textContent).toMatch(/^estimé \d{2}:\d{2}$/);
  });

  it("n'affiche rien avant le premier poll", () => {
    const { handle } = renderSheet("apercu", null);

    expect(handle.textContent).toBe("");
  });
});

describe("Sheet — fin de geste", () => {
  it("termine le glissement sur un pointercancel, comme sur un pointerup", () => {
    // Le système peut confisquer le pointeur (appel entrant, geste système) : sans ce
    // gestionnaire, la feuille resterait collée au doigt disparu.
    const { onCranChange, handle } = renderSheet("moitie");

    firePointer(handle, "pointerdown", 400);
    firePointer(handle, "pointermove", 100);
    fireEvent(handle, new MouseEvent("pointercancel", { bubbles: true, cancelable: true }));

    // 422 + 300 = 722, plus proche de 760 (plein) que de 422 (moitié) — même issue qu'un lâcher.
    expect(onCranChange).toHaveBeenCalledWith("plein");
  });

  it("garde un toucher de moins de 7 px pour un clic, et glisse au-delà", () => {
    // MOVE_THRESHOLD = 6 : un toucher sans déplacement réel doit rester un clic, sinon la
    // poignée devient inerte au moindre tremblement de doigt.
    const court = renderSheet("apercu");
    firePointer(court.handle, "pointerdown", 400);
    firePointer(court.handle, "pointermove", 394); // 6 px : encore ambigu
    firePointer(court.handle, "pointerup", 394);
    court.onCranChange.mockClear();
    fireEvent.click(court.handle, { detail: 1 });
    // `moved` est resté faux : le clic natif avance d'un cran.
    expect(court.onCranChange).toHaveBeenCalledWith("moitie");
    cleanup();

    const long = renderSheet("apercu");
    firePointer(long.handle, "pointerdown", 400);
    firePointer(long.handle, "pointermove", 393); // 7 px : c'est un glissement
    firePointer(long.handle, "pointerup", 393);
    long.onCranChange.mockClear();
    fireEvent.click(long.handle, { detail: 1 });
    // `moved` est vrai : le clic natif qui suit le geste ne doit pas avancer d'un cran de plus.
    expect(long.onCranChange).not.toHaveBeenCalled();
  });
});
