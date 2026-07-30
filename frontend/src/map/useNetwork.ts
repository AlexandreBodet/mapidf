import { useEffect, useState } from "react";
import type { Map as MlMap, GeoJSONSource } from "maplibre-gl";
import { fetchNetwork } from "../api/network";
import { lightenForTrack } from "../ui/color";
import { whenStyleReady } from "./mapReady";
import type { NetworkResponse } from "../api/types";

/**
 * Charge le réseau en un appel et pose DEUX sources pour tout le réseau : `line-shapes`
 * (une feature par branche, coloriée par sa propriété) et `stops` (stations dédoublonnées
 * côté serveur). Le nombre de lignes n'ajoute donc aucune couche.
 */
export function useNetwork(map: MlMap | null, visibleLines: Set<string> | null): NetworkResponse | null {
  const [network, setNetwork] = useState<NetworkResponse | null>(null);

  useEffect(() => {
    if (!map) {
      return;
    }
    let cancelled = false;
    let cancelReady: (() => void) | null = null;
    let cleanupCursors: (() => void) | null = null;

    fetchNetwork().then((data) => {
      if (cancelled) {
        return;
      }
      setNetwork(data);
      const colorByLine = new Map(data.lines.map((line) => [line.id, line.color]));

      const draw = () => {
        if (cancelled || map.getSource("line-shapes")) {
          return;
        }
        map.addSource("line-shapes", {
          type: "geojson",
          data: {
            type: "FeatureCollection",
            features: data.shapes.map((shape) => ({
              type: "Feature",
              properties: {
                lineId: shape.lineId,
                trackColor: lightenForTrack(colorByLine.get(shape.lineId) ?? "#000000"),
              },
              geometry: { type: "LineString", coordinates: shape.coordinates },
            })),
          },
        });
        // Opacité pleine sur une couleur éclaircie : voir lightenForTrack. Une seule couche
        // pour les 37 branches, coloriée par feature.
        map.addLayer({
          id: "line-shapes",
          type: "line",
          source: "line-shapes",
          paint: { "line-color": ["get", "trackColor"], "line-width": 4, "line-opacity": 1 },
        });

        map.addSource("stops", {
          type: "geojson",
          data: {
            type: "FeatureCollection",
            features: data.stations.map((station) => ({
              type: "Feature",
              properties: {
                id: station.id,
                name: station.name,
                // Une correspondance dessert plusieurs lignes : on prend la première pour
                // l'anneau. Le panneau, lui, montre bien toutes ses lignes.
                color: colorByLine.get(station.lineIds[0]) ?? "#666666",
              },
              geometry: { type: "Point", coordinates: [station.lng, station.lat] },
            })),
          },
        });
        map.addLayer({
          id: "stops",
          type: "circle",
          source: "stops",
          minzoom: 11,
          paint: {
            "circle-radius": 5,
            "circle-color": "#fff",
            "circle-stroke-color": ["get", "color"],
            "circle-stroke-width": 2,
          },
        });
        // Noms seulement en zoom rapproché (collision gérée par MapLibre) : coût maîtrisé
        // même avec 321 stations.
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
          paint: { "text-color": "#111", "text-halo-color": "#fff", "text-halo-width": 1.5 },
        });
        map.addLayer({
          id: "stops-selected",
          type: "circle",
          source: "stops",
          minzoom: 11,
          filter: ["==", ["get", "id"], "__none__"],
          paint: {
            "circle-radius": 10,
            "circle-color": "rgba(29,78,216,0.15)",
            "circle-stroke-color": "#1d4ed8",
            "circle-stroke-width": 3,
          },
        });

        const cursorEnter = () => { map.getCanvas().style.cursor = "pointer"; };
        const cursorLeave = () => { map.getCanvas().style.cursor = ""; };
        map.on("mouseenter", "stops", cursorEnter);
        map.on("mouseleave", "stops", cursorLeave);
        map.on("mouseenter", "stops-labels", cursorEnter);
        map.on("mouseleave", "stops-labels", cursorLeave);
        cleanupCursors = () => {
          map.off("mouseenter", "stops", cursorEnter);
          map.off("mouseleave", "stops", cursorLeave);
          map.off("mouseenter", "stops-labels", cursorEnter);
          map.off("mouseleave", "stops-labels", cursorLeave);
        };
      };
      cancelReady = whenStyleReady(map, draw);
    });

    return () => {
      cancelled = true;
      cancelReady?.();
      cleanupCursors?.();
    };
  }, [map]);

  // Filtre client : aucun appel réseau. Les tracés se filtrent par expression ; les stations
  // demandent un recalcul de la collection, car une expression MapLibre sur un tableau
  // lineIds est malcommode — 321 features, c'est trivial.
  useEffect(() => {
    if (!map || !network || !map.getSource("stops")) {
      return;
    }
    const colorByLine = new Map(network.lines.map((line) => [line.id, line.color]));
    map.setFilter("line-shapes", visibleLines
      ? ["in", ["get", "lineId"], ["literal", [...visibleLines]]]
      : null);
    const stations = network.stations.filter(
      (station) => !visibleLines || station.lineIds.some((id) => visibleLines.has(id)));
    (map.getSource("stops") as GeoJSONSource).setData({
      type: "FeatureCollection",
      features: stations.map((station) => ({
        type: "Feature",
        properties: {
          id: station.id,
          name: station.name,
          color: colorByLine.get(
            station.lineIds.find((id) => !visibleLines || visibleLines.has(id)) ?? station.lineIds[0]
          ) ?? "#666666",
        },
        geometry: { type: "Point", coordinates: [station.lng, station.lat] },
      })),
    });
  }, [map, network, visibleLines]);

  return network;
}
