import { useEffect, useRef, useState } from "react";
import maplibregl from "maplibre-gl";
import { useMap } from "./map/MapView";
import { useLineShape } from "./map/useLineShape";
import { useVehicles } from "./map/useVehicles";
import { VehiclePanel } from "./ui/VehiclePanel";
import { LINE_ID } from "./api/config";

type Selected = { headsign: string; nextStop: string; status: string; source: string } | null;

export default function App() {
  const container = useRef<HTMLDivElement>(null);
  const map = useMap(container);
  const [selected, setSelected] = useState<Selected>(null);
  const [selectedTripId, setSelectedTripId] = useState<string | null>(null);
  const [follow, setFollow] = useState(false);
  useLineShape(map, LINE_ID);
  useVehicles(map, LINE_ID, selectedTripId, follow);

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
    const onMoveStart = (e: maplibregl.MapLibreEvent) => {
      if ((e as { originalEvent?: unknown }).originalEvent) {
        setFollow(false);
      }
    };
    map.on("movestart", onMoveStart);
    return () => {
      map.off("movestart", onMoveStart);
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
        onFollow={() => setFollow(true)}
        onClose={clearSelection}
      />
    </>
  );
}
