import { beforeEach } from "vitest";

/**
 * `Sheet` installe un ResizeObserver au montage, et jsdom n'en a pas : sans ce stub, le composant
 * lève. Il n'observe rien — c'est au test de dire quand la mesure tombe, via `triggerResize`.
 */
class ResizeObserverStub {
  static instances: ResizeObserverStub[] = [];

  constructor(private readonly callback: ResizeObserverCallback) {
    ResizeObserverStub.instances.push(this);
  }

  observe() {}
  unobserve() {}
  disconnect() {}

  fire() {
    this.callback([], this as unknown as ResizeObserver);
  }
}

// Ce fichier est chargé AUSSI pour les tests de fonctions pures, qui tournent en environnement
// Node : sans ce garde, `Element` n'existe pas et tous ces tests casseraient.
if (typeof Element !== "undefined") {
  globalThis.ResizeObserver = ResizeObserverStub as unknown as typeof ResizeObserver;
  // jsdom LÈVE sur ces deux méthodes : le premier geste de chaque test échouerait.
  Element.prototype.setPointerCapture = () => {};
  Element.prototype.releasePointerCapture = () => {};
}

beforeEach(() => {
  ResizeObserverStub.instances.length = 0;
});

/** Déclenche la mesure de tous les observers installés. */
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
