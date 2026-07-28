import { useEffect, useMemo, useRef, useState } from "react";
import maplibregl from "maplibre-gl";
import { useMap } from "./map/MapView";
import { useLineShape } from "./map/useLineShape";
import { useVehicles } from "./map/useVehicles";
import { VehiclePanel } from "./ui/VehiclePanel";
import { StopPanel } from "./ui/StopPanel";
import { Legend } from "./ui/Legend";
import { fetchShape, fetchDepartures } from "./api/lines";
import { LINE_ID, VEHICLE_POLL_MS } from "./api/config";
import type { VehiclesResponse, DeparturesResponse } from "./api/types";

type V = VehiclesResponse["vehicles"][number];
type Selected = { headsign: string; nextStop: string; status: string; source: string; expectedTime: string } | null;

function toSelected(v: V): Selected {
  return { headsign: v.headsign, nextStop: v.nextStop, status: v.status, source: v.source, expectedTime: v.expectedTime };
}

export default function App() {
  const container = useRef<HTMLDivElement>(null);
  const map = useMap(container);
  const departuresAbort = useRef<AbortController | null>(null);
  const [selected, setSelected] = useState<Selected>(null);
  const [selectedTripId, setSelectedTripId] = useState<string | null>(null);
  const [follow, setFollow] = useState(false);
  const [station, setStation] = useState<DeparturesResponse | null>(null);
  const [selectedStationId, setSelectedStationId] = useState<string | null>(null);
  const [lineColor, setLineColor] = useState("#e30613");
  const [count, setCount] = useState(0);
  // Trains concernés par les passages de la station ouverte (surlignés sur la carte).
  const highlightedTripIds = useMemo(
    () => new Set(station?.directions.flatMap((d) => d.passages.map((p) => p.journeyRef)) ?? []),
    [station],
  );
  useLineShape(map, LINE_ID);
  useEffect(() => {
    fetchShape(LINE_ID).then((s) => setLineColor(s.color)).catch(() => {});
  }, []);
  // À chaque poll, rafraîchit le panneau avec la donnée fraîche du train suivi
  // (prochain arrêt + ETA vivants). Si le train quitte le flux, on garde le dernier état connu.
  useVehicles(map, LINE_ID, lineColor, selectedTripId, follow, (v) => {
    if (v) {
      setSelected(toSelected(v));
    }
  }, setCount, highlightedTripIds);

  useEffect(() => {
    if (!map) {
      return;
    }
    const onClick = (e: maplibregl.MapLayerMouseEvent) => {
      const props = e.features?.[0]?.properties;
      if (!props) {
        return;
      }
      setStation(null);
      setSelectedStationId(null);
      map.setFilter("stops-selected", ["==", ["get", "id"], "__none__"]);
      setSelected(props as Selected);
      setSelectedTripId(props.tripId as string);
      setFollow(true);
    };
    map.on("click", "vehicles", onClick);
    const onStationClick = async (e: maplibregl.MapLayerMouseEvent) => {
      const id = e.features?.[0]?.properties?.id as string | undefined;
      const coords = (e.features?.[0]?.geometry as GeoJSON.Point | undefined)?.coordinates;
      if (!id) {
        return;
      }
      // Sélection exclusive : ouvrir une station ferme le suivi d'un train.
      setSelected(null);
      setSelectedTripId(null);
      setFollow(false);
      map.setFilter("stops-selected", ["==", ["get", "id"], id]);
      if (coords) {
        map.easeTo({ center: coords as [number, number] });
      }
      setSelectedStationId(id);
      departuresAbort.current?.abort();
      const controller = new AbortController();
      departuresAbort.current = controller;
      try {
        const fresh = await fetchDepartures(LINE_ID, id, controller.signal);
        if (!controller.signal.aborted) {
          setStation(fresh);
        }
      } catch {
        if (!controller.signal.aborted) {
          setStation(null);
        }
      }
    };
    map.on("click", "stops", onStationClick);
    map.on("click", "stops-labels", onStationClick);
    const enter = () => { map.getCanvas().style.cursor = "pointer"; };
    const leave = () => { map.getCanvas().style.cursor = ""; };
    map.on("mouseenter", "vehicles", enter);
    map.on("mouseleave", "vehicles", leave);
    return () => {
      map.off("click", "vehicles", onClick);
      map.off("click", "stops", onStationClick);
      map.off("click", "stops-labels", onStationClick);
      map.off("mouseenter", "vehicles", enter);
      map.off("mouseleave", "vehicles", leave);
    };
  }, [map]);

  useEffect(() => {
    if (!map) {
      return;
    }
    // Le suivi appelle jumpTo à chaque frame : la carte est en mouvement permanent,
    // donc `movestart` ne se redéclenche pas sur un geste utilisateur. On écoute plutôt
    // les événements d'entrée bruts, qui arrivent quel que soit l'état d'animation.
    const stopFollow = () => setFollow(false);
    map.on("mousedown", stopFollow);
    map.on("wheel", stopFollow);
    map.on("touchstart", stopFollow);
    return () => {
      map.off("mousedown", stopFollow);
      map.off("wheel", stopFollow);
      map.off("touchstart", stopFollow);
    };
  }, [map]);

  // Le panneau passages est rafraîchi au rythme du poll tant qu'une station est sélectionnée,
  // pour que les ETA vivent et que les passages partis disparaissent (sinon on affiche des
  // « imminent » fantômes figés au fetch initial).
  useEffect(() => {
    if (!selectedStationId) {
      return;
    }
    let cancelled = false;
    let timer: number;
    const controller = new AbortController();
    const tick = async () => {
      try {
        const fresh = await fetchDepartures(LINE_ID, selectedStationId, controller.signal);
        if (!cancelled) {
          setStation(fresh);
        }
      } catch {
        // on conserve l'affichage courant
      }
      if (!cancelled) {
        timer = window.setTimeout(tick, VEHICLE_POLL_MS);
      }
    };
    timer = window.setTimeout(tick, VEHICLE_POLL_MS);
    return () => {
      cancelled = true;
      window.clearTimeout(timer);
      controller.abort();
    };
  }, [selectedStationId]);

  const clearSelection = () => {
    setSelected(null);
    setSelectedTripId(null);
    setFollow(false);
  };

  const closeStation = () => {
    departuresAbort.current?.abort();
    departuresAbort.current = null;
    setStation(null);
    setSelectedStationId(null);
    map?.setFilter("stops-selected", ["==", ["get", "id"], "__none__"]);
  };

  const followTrainFromPanel = (tripId: string) => {
    closeStation();
    setSelected(null);
    setSelectedTripId(tripId);
    setFollow(true);
  };

  return (
    <>
      <div ref={container} style={{ position: "absolute", inset: 0 }} />
      <VehiclePanel
        vehicle={selected}
        following={follow}
        onFollow={() => setFollow((f) => !f)}
        onClose={clearSelection}
      />
      <StopPanel data={station} onClose={closeStation} onSelectTrain={followTrainFromPanel} />
      <Legend color={lineColor} count={count} />
    </>
  );
}
