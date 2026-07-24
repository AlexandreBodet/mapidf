import { useEffect, useRef, useState } from "react";
import maplibregl from "maplibre-gl";
import { useMap } from "./map/MapView";
import { useLineShape } from "./map/useLineShape";
import { useVehicles } from "./map/useVehicles";
import { VehiclePanel } from "./ui/VehiclePanel";
import { StopPanel } from "./ui/StopPanel";
import { Legend } from "./ui/Legend";
import { fetchShape, fetchDepartures } from "./api/lines";
import { LINE_ID } from "./api/config";
import type { VehiclesResponse, DeparturesResponse } from "./api/types";

type V = VehiclesResponse["vehicles"][number];
type Selected = { headsign: string; nextStop: string; status: string; source: string; expectedTime: string } | null;

function toSelected(v: V): Selected {
  return { headsign: v.headsign, nextStop: v.nextStop, status: v.status, source: v.source, expectedTime: v.expectedTime };
}

export default function App() {
  const container = useRef<HTMLDivElement>(null);
  const map = useMap(container);
  const [selected, setSelected] = useState<Selected>(null);
  const [selectedTripId, setSelectedTripId] = useState<string | null>(null);
  const [follow, setFollow] = useState(false);
  const [station, setStation] = useState<DeparturesResponse | null>(null);
  const [lineColor, setLineColor] = useState("#e30613");
  const [count, setCount] = useState(0);
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
  }, setCount);

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
      setSelected(props as Selected);
      setSelectedTripId(props.tripId as string);
      setFollow(true);
    };
    map.on("click", "vehicles", onClick);
    const onStationClick = async (e: maplibregl.MapLayerMouseEvent) => {
      const id = e.features?.[0]?.properties?.id as string | undefined;
      if (!id) {
        return;
      }
      // Sélection exclusive : ouvrir une station ferme le suivi d'un train.
      setSelected(null);
      setSelectedTripId(null);
      setFollow(false);
      try {
        setStation(await fetchDepartures(LINE_ID, id));
      } catch {
        setStation(null);
      }
    };
    map.on("click", "stops", onStationClick);
    const enter = () => { map.getCanvas().style.cursor = "pointer"; };
    const leave = () => { map.getCanvas().style.cursor = ""; };
    map.on("mouseenter", "vehicles", enter);
    map.on("mouseleave", "vehicles", leave);
    return () => {
      map.off("click", "vehicles", onClick);
      map.off("click", "stops", onStationClick);
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

  const clearSelection = () => {
    setSelected(null);
    setSelectedTripId(null);
    setFollow(false);
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
      <StopPanel data={station} onClose={() => setStation(null)} />
      <Legend color={lineColor} count={count} />
    </>
  );
}
