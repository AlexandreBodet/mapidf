import { useEffect, useRef, useState } from "react";
import maplibregl from "maplibre-gl";
import { useMap } from "./map/MapView";
import { useLineShape } from "./map/useLineShape";
import { useVehicles } from "./map/useVehicles";
import { VehiclePanel } from "./ui/VehiclePanel";
import { LINE_ID } from "./api/config";
import type { VehiclesResponse } from "./api/types";

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
  useLineShape(map, LINE_ID);
  // À chaque poll, rafraîchit le panneau avec la donnée fraîche du train suivi
  // (prochain arrêt + ETA vivants). Si le train quitte le flux, on garde le dernier état connu.
  useVehicles(map, LINE_ID, selectedTripId, follow, (v) => {
    if (v) {
      setSelected(toSelected(v));
    }
  });

  useEffect(() => {
    if (!map) {
      return;
    }
    const onClick = (e: maplibregl.MapLayerMouseEvent) => {
      const props = e.features?.[0]?.properties;
      if (!props) {
        return;
      }
      setSelected(props as Selected);
      setSelectedTripId(props.tripId as string);
      setFollow(true);
    };
    map.on("click", "vehicles", onClick);
    return () => {
      map.off("click", "vehicles", onClick);
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
    </>
  );
}
