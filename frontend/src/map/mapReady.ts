import type { Map as MlMap } from "maplibre-gl";

// Ajoute nos sources/couches dès que c'est possible, en appelant `draw`.
//
// Piège MapLibre : isStyleLoaded() et l'événement "load" exigent que TOUT soit
// chargé (toutes les tuiles du fond, sprite, glyphs). Avec un fond vectoriel
// externe (OpenFreeMap Liberty) ce n'est presque jamais « au repos », donc
// attendre ces signaux fait apparaître le tracé après plusieurs secondes… voire
// une minute, selon le réseau/navigateur.
//
// Or addSource/addLayer n'exigent QUE le spec de style analysé (sinon MapLibre
// lève « Style is not done loading. »), ce qui arrive bien plus tôt. On tente donc
// le tracé immédiatement, et à défaut on réessaie à chaque "styledata" (émis dès
// que le spec est prêt, avant la fin du chargement des tuiles). Renvoie une
// fonction d'annulation pour le démontage.
export function whenStyleReady(map: MlMap, draw: () => void): () => void {
  let cancelled = false;

  const attempt = (): boolean => {
    if (cancelled) {
      return true;
    }
    try {
      draw();
      return true;
    } catch (err) {
      // Spec de style pas encore analysé : on réessaiera au prochain "styledata".
      if (err instanceof Error && err.message.includes("not done loading")) {
        return false;
      }
      throw err;
    }
  };

  if (attempt()) {
    return () => {
      cancelled = true;
    };
  }

  const onData = () => {
    if (attempt()) {
      map.off("styledata", onData);
    }
  };
  map.on("styledata", onData);
  return () => {
    cancelled = true;
    map.off("styledata", onData);
  };
}
