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
          paint: { "line-color": shape.color, "line-width": 4, "line-opacity": 0.45 },
        });
        map.addSource("stops", {
          type: "geojson",
          data: {
            type: "FeatureCollection",
            features: shape.stops.map((s) => ({
              type: "Feature",
              properties: { id: s.id, name: s.name },
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
        // Noms affichés seulement en zoom rapproché (collision gérée par MapLibre) → pas
        // d'encombrement au dézoom, coût maîtrisé même avec beaucoup de stations.
        map.addLayer({
          id: "stops-labels",
          type: "symbol",
          source: "stops",
          minzoom: 12,
          layout: {
            "text-field": ["get", "name"],
            "text-font": ["Noto Sans Regular"],
            "text-size": 12,
            "text-offset": [0, 1.2],
            "text-anchor": "top",
          },
          paint: {
            "text-color": "#111",
            "text-halo-color": "#fff",
            "text-halo-width": 1.5,
          },
        });
        // Curseur main au survol des stations cliquables.
        map.on("mouseenter", "stops", () => { map.getCanvas().style.cursor = "pointer"; });
        map.on("mouseleave", "stops", () => { map.getCanvas().style.cursor = ""; });
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
