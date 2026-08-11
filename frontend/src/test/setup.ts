import { beforeEach } from "vitest";

/**
 * `Sheet` installe un ResizeObserver au montage, et jsdom n'en a pas : sans ce stub, le composant
 * lève. Rien ne se mesure tout seul — c'est au test de dire quand, via `triggerResize`.
 */
class ResizeObserverStub {
  static instances: ResizeObserverStub[] = [];
  // Hauteur connue par élément observé, prise à l'observation puis à chaque tir : un observer
  // sans élément restant (jamais observé, débranché, ou observant un élément dont la hauteur
  // n'a pas bougé) ne doit rien rappeler — sinon un `render` + `unmount` + `render` rappellerait
  // le callback du composant démonté, ou observer le mauvais élément passerait pour correct.
  private readonly heights = new Map<Element, number>();

  constructor(private readonly callback: ResizeObserverCallback) {
    ResizeObserverStub.instances.push(this);
  }

  observe(element: Element) { this.heights.set(element, element.getBoundingClientRect().height); }
  unobserve(element: Element) { this.heights.delete(element); }
  disconnect() { this.heights.clear(); }

  fire() {
    const changed = [...this.heights.keys()]
      .filter((el) => el.getBoundingClientRect().height !== this.heights.get(el));
    if (changed.length === 0) {
      return;
    }
    for (const el of changed) {
      this.heights.set(el, el.getBoundingClientRect().height);
    }
    this.callback(changed.map((target) => ({ target }) as ResizeObserverEntry), this);
  }
}

// Ce fichier est chargé AUSSI pour les tests de fonctions pures, qui tournent en environnement
// Node : sans ce garde, `Element` n'existe pas et tous ces tests casseraient.
if (typeof Element !== "undefined") {
  globalThis.ResizeObserver = ResizeObserverStub;
  // jsdom 27 ne définit pas ces deux méthodes du tout (jsdom 26 les avait, mais elles levaient) :
  // dans les deux cas, le premier geste de chaque test échouerait.
  Element.prototype.setPointerCapture = () => {};
  Element.prototype.releasePointerCapture = () => {};
}

beforeEach(() => {
  ResizeObserverStub.instances.length = 0;
});

/**
 * Déclenche la mesure de tous les observers installés. Un test qui asserte sur le rendu (pas
 * sur un `vi.fn()` appelé dans le callback) doit l'envelopper dans `act(...)`.
 *
 * Ne rappelle que les observers dont un élément a **changé** de hauteur : deux appels de suite
 * sans `stubHeight` entre les deux ne produisent qu'un seul rappel.
 */
export function triggerResize() {
  for (const observer of ResizeObserverStub.instances) {
    observer.fire();
  }
}

/**
 * jsdom renvoie 0 pour toute mesure. Un stub par élément, pas sur le prototype : un test qui
 * impose une hauteur doit dire de quel élément il parle.
 */
export function stubHeight(element: Element, px: number) {
  element.getBoundingClientRect = () => ({
    height: px, width: 0, top: 0, left: 0, right: 0, bottom: px, x: 0, y: 0,
    toJSON: () => ({}),
  });
}
