import { useEffect, useState } from "react";
import maplibregl, { Map as MlMap } from "maplibre-gl";

export function useMap(container: React.RefObject<HTMLDivElement>): MlMap | null {
  const [map, setMap] = useState<MlMap | null>(null);
  useEffect(() => {
    if (!container.current) {
      return;
    }
    const instance = new maplibregl.Map({
      container: container.current,
      // Fond de rues OpenFreeMap (gratuit, sans clé) — pour se repérer dans Paris.
      // demotiles n'affiche que les frontières (aucune rue).
      style: "https://tiles.openfreemap.org/styles/liberty",
      center: [2.34, 48.86],
      zoom: 11,
    });
    setMap(instance);
    return () => {
      instance.remove();
      setMap(null);
    };
  }, [container]);
  return map;
}
