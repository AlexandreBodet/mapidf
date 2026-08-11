import { useEffect, useRef, useState } from "react";
import type { Map as MlMap, GeoJSONSource, FilterSpecification } from "maplibre-gl";
import { fetchNetwork } from "../api/network";
import { lightenForTrack } from "../ui/color";
import { whenStyleReady } from "./mapReady";
import type { NetworkResponse, Severity } from "../api/types";

/**
 * `empty` = le backend répond 200 avec un réseau vide : c'est le premier démarrage, où il charge
 * le GTFS (~125 Mo) avant d'avoir quoi que ce soit à servir. Ni une panne, ni une erreur.
 */
export type NetworkStatus = "loading" | "empty" | "error" | "ready";

// Le réseau est statique une fois chargé : on ne réessaie que tant qu'il manque.
const RETRY_MS = 10_000;

// Filtre « aucune station » : une liste vide ne correspond à rien et ne dessine rien.
const STATION_NONE: FilterSpecification = ["in", ["get", "id"], ["literal", []]];

/** Ids triés : `setFilter` sort tôt sur un filtre identique terme à terme, or l'ordre d'un Map
 *  varie d'un poll à l'autre — sans le tri, chaque poll rechargerait la source pour rien. */
function stationFilter(ids: string[]): FilterSpecification {
  return ["in", ["get", "id"], ["literal", [...ids].sort()]];
}

/**
 * Pose les deux anneaux. Appelé à la création des couches ET à chaque changement de
 * perturbations : les couches naissent après le fetch réseau, donc l'ordre des deux n'est pas
 * garanti — sans le premier appel, des perturbations déjà connues attendraient le poll suivant.
 */
function applyDisruptionRings(map: MlMap, stationSeverity: Map<string, Severity>,
                              emphasize: boolean) {
  if (!map.getLayer("stops-blocked")) {
    return;
  }
  const idsOf = (severity: Severity) =>
    [...stationSeverity.entries()].filter(([, value]) => value === severity).map(([id]) => id);
  const blocked = idsOf("BLOQUANTE");
  const disrupted = idsOf("PERTURBEE");
  map.setFilter("stops-blocked", stationFilter(blocked));
  map.setFilter("stops-disrupted", stationFilter(disrupted));
  // Le halo n'est qu'un « regarde ici », posé le temps que le panneau est ouvert : la gravité,
  // elle, reste lisible en permanence dans le rond.
  map.setFilter("stops-disruption-halo",
    stationFilter(emphasize ? [...blocked, ...disrupted] : []));
}

/**
 * Charge le réseau en un appel et pose DEUX sources pour tout le réseau : `line-shapes`
 * (une feature par branche, coloriée par sa propriété) et `stops` (stations dédoublonnées
 * côté serveur). Le nombre de lignes n'ajoute donc aucune couche.
 */
export function useNetwork(
  map: MlMap | null,
  visibleLines: Set<string> | null,
  stationSeverity: Map<string, Severity> = new Map(),
  /** Panneau des perturbations ouvert : les stations concernées reçoivent un halo. */
  emphasizeDisruptions = false,
): {
  network: NetworkResponse | null;
  status: NetworkStatus;
} {
  const [network, setNetwork] = useState<NetworkResponse | null>(null);
  const [status, setStatus] = useState<NetworkStatus>("loading");
  // Lu par `draw`, qui vit hors du cycle de rendu et doit voir la dernière valeur connue.
  const severityRef = useRef(stationSeverity);
  severityRef.current = stationSeverity;
  const emphasizeRef = useRef(emphasizeDisruptions);
  emphasizeRef.current = emphasizeDisruptions;

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
      // Le bandeau reste en langage courant : la cause exacte n'a d'intérêt que pour qui ouvre
      // la console.
      console.warn("[network] chargement du réseau impossible, nouvelle tentative :", error);
      setStatus("error");
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
        setStatus("empty");
        timer = window.setTimeout(load, RETRY_MS);
        return;
      }
      setNetwork(data);
      setStatus("ready");
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
        // Halo d'emphase, glissé SOUS les ronds de station (beforeId) pour ne pas les teinter.
        // Neutre : la couleur de gravité est déjà dans le rond.
        map.addLayer({
          id: "stops-disruption-halo",
          type: "circle",
          source: "stops",
          minzoom: 11,
          filter: STATION_NONE,
          paint: { "circle-radius": 12, "circle-color": "rgba(17,17,17,0.14)" },
        }, "stops");
        // Gravité PEINTE DANS le rond (même rayon que `stops`), et non en anneau supplémentaire :
        // un troisième cercle autour des stations saturait la carte. Le liseré garde la couleur
        // de la ligne, donc la station ne perd pas son identité.
        // Une couche par gravité plutôt qu'une couleur pilotée par la donnée, pour rester sur
        // `setFilter` — la couleur par propriété obligerait à réécrire la source à chaque poll.
        // INFORMATION n'est pas peinte : elle n'empêche pas de voyager.
        for (const [id, color] of [["stops-blocked", "#b91c1c"], ["stops-disrupted", "#b45309"]]) {
          map.addLayer({
            id,
            type: "circle",
            source: "stops",
            minzoom: 11,
            filter: STATION_NONE,
            paint: {
              "circle-radius": 5,
              "circle-color": color,
              "circle-stroke-color": ["get", "color"],
              "circle-stroke-width": 2,
            },
          });
        }

        applyDisruptionRings(map, severityRef.current, emphasizeRef.current);

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
    // Tir-et-oublie assumé : `load` gère ses propres échecs (`failed`, qui allume le bandeau).
    void load();

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
    // `setData` rend une Promise depuis MapLibre 6 (elle rendait `this` en 4) : on l'écarte
    // explicitement, l'application des données n'a pas à bloquer le rendu.
    void map.getSource<GeoJSONSource>("stops")!.setData({
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

  useEffect(() => {
    if (map) {
      applyDisruptionRings(map, stationSeverity, emphasizeDisruptions);
    }
  }, [map, network, stationSeverity, emphasizeDisruptions]);

  return { network, status };
}
