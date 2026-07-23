import { useEffect } from "react";
import type { Map as MlMap } from "maplibre-gl";
import { fetchShape } from "../api/lines";
import { whenStyleReady } from "./mapReady";

export function useLineShape(map: MlMap | null, lineId: string) {
  useEffect(() => {
    if (!map) {
      return;
    }
    let cancelled = false;
    let cancelReady: (() => void) | null = null;
    fetchShape(lineId).then((shape) => {
      if (cancelled) {
        return;
      }
      const draw = () => {
        if (cancelled || map.getSource("line-shape")) {
          return;
        }
        map.addSource("line-shape", {
          type: "geojson",
          data: {
            type: "Feature",
            properties: {},
            geometry: { type: "LineString", coordinates: shape.shape },
          },
        });
        map.addLayer({
          id: "line-shape",
          type: "line",
          source: "line-shape",
          paint: { "line-color": shape.color, "line-width": 4 },
        });
        map.addSource("stops", {
          type: "geojson",
          data: {
            type: "FeatureCollection",
            features: shape.stops.map((s) => ({
              type: "Feature",
              properties: { name: s.name },
              geometry: { type: "Point", coordinates: [s.lng, s.lat] },
            })),
          },
        });
        map.addLayer({
          id: "stops",
          type: "circle",
          source: "stops",
          paint: {
            "circle-radius": 5,
            "circle-color": "#fff",
            "circle-stroke-color": shape.color,
            "circle-stroke-width": 2,
          },
        });
      };
      cancelReady = whenStyleReady(map, draw);
    });
    return () => {
      cancelled = true;
      if (cancelReady) {
        cancelReady();
      }
    };
  }, [map, lineId]);
}
