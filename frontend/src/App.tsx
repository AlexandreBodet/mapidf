import { useEffect, useRef, useState } from "react";
import maplibregl from "maplibre-gl";
import { useMap } from "./map/MapView";
import { useLineShape } from "./map/useLineShape";
import { useVehicles } from "./map/useVehicles";
import { VehiclePanel } from "./ui/VehiclePanel";
import { LINE_ID } from "./api/config";

type Selected = { headsign: string; nextStop: string; delaySec: number; source: string } | null;

export default function App() {
  const container = useRef<HTMLDivElement>(null);
  const map = useMap(container);
  const [selected, setSelected] = useState<Selected>(null);
  useLineShape(map, LINE_ID);
  useVehicles(map, LINE_ID);

  useEffect(() => {
    if (!map) {
      return;
    }
    const onClick = (e: maplibregl.MapLayerMouseEvent) => {
      const props = e.features?.[0]?.properties;
      setSelected(props ? (props as Selected) : null);
    };
    map.on("click", "vehicles", onClick);
    return () => {
      map.off("click", "vehicles", onClick);
    };
  }, [map]);

  return (
    <>
      <div ref={container} style={{ position: "absolute", inset: 0 }} />
      <VehiclePanel vehicle={selected} onClose={() => setSelected(null)} />
    </>
  );
}
