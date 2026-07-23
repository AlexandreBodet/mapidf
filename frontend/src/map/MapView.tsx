import { useEffect, useRef } from "react";
import maplibregl, { Map as MlMap } from "maplibre-gl";

export function useMap(container: React.RefObject<HTMLDivElement>) {
  const mapRef = useRef<MlMap | null>(null);
  useEffect(() => {
    if (!container.current || mapRef.current) {
      return;
    }
    mapRef.current = new maplibregl.Map({
      container: container.current,
      style: "https://demotiles.maplibre.org/style.json",
      center: [2.34, 48.86],
      zoom: 11,
    });
    return () => {
      mapRef.current?.remove();
      mapRef.current = null;
    };
  }, [container]);
  return mapRef;
}
