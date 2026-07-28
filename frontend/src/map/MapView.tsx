import { useEffect, useRef, useState } from "react";
import maplibregl, { Map as MlMap } from "maplibre-gl";

export function useMap(container: React.RefObject<HTMLDivElement>): MlMap | null {
  const [map, setMap] = useState<MlMap | null>(null);
  // On garde l'instance et un éventuel timer de destruction hors du cycle de rendu.
  const ref = useRef<{ instance: MlMap | null; pending: number }>({ instance: null, pending: 0 });
  useEffect(() => {
    if (!container.current) {
      return;
    }
    // React StrictMode (dev) relance chaque effet setup → cleanup → setup. Détruire
    // le contexte WebGL puis en recréer un aussitôt fait perdre le contexte survivant
    // sous Firefox (carte qui s'affiche une frame puis devient blanche, sans
    // « webglcontextrestored »). On crée donc UNE seule instance et on diffère sa
    // destruction : si un nouveau setup arrive immédiatement (StrictMode), on annule
    // la destruction et on réutilise la même carte. Un vrai démontage la détruit bien.
    if (ref.current.pending) {
      window.clearTimeout(ref.current.pending);
      ref.current.pending = 0;
    }
    if (!ref.current.instance) {
      ref.current.instance = new maplibregl.Map({
        container: container.current,
        // Fond de rues OpenFreeMap (gratuit, sans clé) — pour se repérer dans Paris.
        // demotiles n'affiche que les frontières (aucune rue).
        style: "https://tiles.openfreemap.org/styles/liberty",
        center: [2.34, 48.86],
        zoom: 11,
      });
      // Le style de fond (OpenFreeMap Liberty) référence des icônes de POI absentes de son
      // sprite (equestrian, ferry_terminal, office…), ce qui spamme la console d'erreurs
      // « Image X could not be loaded ». On fournit un pixel transparent à la demande : même
      // rendu (ces POI n'apparaissaient déjà pas), console propre. Nos propres images (ex.
      // vehicle-arrow) sont ajoutées explicitement avant leur couche et ne passent pas ici.
      ref.current.instance.on("styleimagemissing", (e) => {
        const map = ref.current.instance;
        if (!map || map.hasImage(e.id)) {
          return;
        }
        map.addImage(e.id, { width: 1, height: 1, data: new Uint8Array(4) });
      });
      ref.current.instance.addControl(
        new maplibregl.NavigationControl({ showCompass: true, visualizePitch: true }),
        "top-left",
      );
    }
    setMap(ref.current.instance);
    return () => {
      const instance = ref.current.instance;
      ref.current.pending = window.setTimeout(() => {
        instance?.remove();
        ref.current.instance = null;
        ref.current.pending = 0;
        setMap(null);
      }, 0);
    };
  }, [container]);
  return map;
}
