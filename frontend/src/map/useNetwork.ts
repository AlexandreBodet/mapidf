import { useEffect, useState } from "react";
import type { Map as MlMap, GeoJSONSource } from "maplibre-gl";
import { fetchNetwork } from "../api/network";
import { lightenForTrack } from "../ui/color";
import { whenStyleReady } from "./mapReady";
import type { NetworkResponse } from "../api/types";

/**
 * `empty` = le backend répond 200 avec un réseau vide : c'est le premier démarrage, où il charge
 * le GTFS (~109 Mo) avant d'avoir quoi que ce soit à servir. Ni une panne, ni une erreur.
 */
export type NetworkStatus = "loading" | "empty" | "error" | "ready";

// Le réseau est statique une fois chargé : on ne réessaie que tant qu'il manque.
const RETRY_MS = 10_000;

/**
 * Charge le réseau en un appel et pose DEUX sources pour tout le réseau : `line-shapes`
 * (une feature par branche, coloriée par sa propriété) et `stops` (stations dédoublonnées
 * côté serveur). Le nombre de lignes n'ajoute donc aucune couche.
 */
export function useNetwork(map: MlMap | null, visibleLines: Set<string> | null): {
  network: NetworkResponse | null;
  status: NetworkStatus;
  detail: string | null;
} {
  const [network, setNetwork] = useState<NetworkResponse | null>(null);
  const [state, setState] = useState<{ status: NetworkStatus; detail: string | null }>({
    status: "loading",
    detail: null,
  });

  useEffect(() => {
    if (!map) {
      return;
    }
    let cancelled = false;
    let timer = 0;
    let cancelReady: (() => void) | null = null;
    let cleanupCursors: (() => void) | null = null;

    const failed = (error: unknown) => {
      if (cancelled) {
        return;
      }
      setState({
        status: "error",
        detail: error instanceof Error ? error.message : String(error),
      });
      timer = window.setTimeout(load, RETRY_MS);
    };

    // Le réseau ne se dessine qu'une fois non vide : dessiner des sources vides au premier
    // démarrage les figerait, le garde de `draw` sortant si `line-shapes` existe déjà.
    // `then(succès, échec)` et non `.catch` : une erreur levée par le dessin MapLibre ne doit
    // pas être maquillée en panne réseau, ni relancer des tentatives.
    const load = (): Promise<void> => fetchNetwork().then((data) => {
      if (cancelled) {
        return;
      }
      if (data.lines.length === 0) {
        setState({ status: "empty", detail: null });
        timer = window.setTimeout(load, RETRY_MS);
        return;
      }
      setNetwork(data);
      setState({ status: "ready", detail: null });
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
        // même avec 321 stations. Seuil 13 et pas 12 : à 12 un nom médian couvre ~2 100 m au
        // sol pour 400 m entre deux stations voisines, donc MapLibre en écartait la majorité ;
        // à 13 on retombe à ~1 060 m. La collision en supprime encore — c'est assumé, il faut
        // ~15 pour les tenir tous. Cf. l'échelle complète commentée dans VehicleLayer.ts.
        map.addLayer({
          id: "stops-labels",
          type: "symbol",
          source: "stops",
          minzoom: 13,
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
    }, failed);
    load();

    return () => {
      cancelled = true;
      window.clearTimeout(timer);
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

  return { network, status: state.status, detail: state.detail };
}
