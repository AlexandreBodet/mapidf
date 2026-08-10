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
 * supporté par jsdom, et on pose `pointerId` en propriété brute (`Sheet` la lit, mais ne la
 * compare jamais). `timeStamp` est figé à une constante **non nulle** : React calcule
 * `event.timeStamp || Date.now()`, donc `0` serait ignoré et remplacé par l'heure réelle,
 * introduisant une vitesse parasite entre deux dispatches synchrones (or la vitesse n'est pas
 * testable ici, cf. le brief — un `timeStamp` figé maintient `elapsed` à 0 dans `applyMove`).
 */
function firePointer(element: Element, type: "pointerdown" | "pointermove" | "pointerup", clientY: number) {
  const event = new MouseEvent(type, { bubbles: true, cancelable: true, clientY });
  Object.defineProperty(event, "pointerId", { value: 1 });
  Object.defineProperty(event, "timeStamp", { value: 1 });
  fireEvent(element, event);
}

function drag(element: Element, fromY: number, toY: number) {
  firePointer(element, "pointerdown", fromY);
  firePointer(element, "pointermove", toY);
  firePointer(element, "pointerup", toY);
}

function renderSheet(cran: Cran, onCranChange = vi.fn(), onPeekHeight = vi.fn()) {
  const view = render(
    <Sheet
      cran={cran}
      onCranChange={onCranChange}
      viewportHeight={VIEWPORT}
      header={null}
      summary={<p>résumé</p>}
      footer={<p>pied</p>}
      alert={null}
      label="État du réseau"
      onPeekHeight={onPeekHeight}
      asOf={null}
    >
      <p>corps</p>
    </Sheet>,
  );
  const handle = screen.getByRole("button", { name: "Changer la hauteur du panneau" });
  return { view, onCranChange, onPeekHeight, handle, body: screen.getByText("corps").parentElement! };
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

describe("Sheet — corps", () => {
  it("laisse le défilement gagner quand le contenu n'est pas remonté en haut", () => {
    const { onCranChange, body } = renderSheet("plein");
    Object.defineProperty(body, "scrollTop", { value: 50, configurable: true });

    drag(body, 100, 500);

    expect(onCranChange).not.toHaveBeenCalled();
  });

  it("replie la feuille quand le contenu est déjà en haut", () => {
    const { onCranChange, body } = renderSheet("plein");
    Object.defineProperty(body, "scrollTop", { value: 0, configurable: true });

    drag(body, 100, 500);

    // 760 - 400 = 360, plus proche de 422 (moitié) que de 44 (aperçu).
    expect(onCranChange).toHaveBeenCalledWith("moitie");
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
