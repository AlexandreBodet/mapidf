import { useRef } from "react";
import { useMap } from "./map/MapView";
import { useLineShape } from "./map/useLineShape";
import { LINE_ID } from "./api/config";

export default function App() {
  const container = useRef<HTMLDivElement>(null);
  const map = useMap(container);
  useLineShape(map, LINE_ID);
  return <div ref={container} style={{ position: "absolute", inset: 0 }} />;
}
