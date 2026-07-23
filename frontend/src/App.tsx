import { useRef } from "react";
import { useMap } from "./map/MapView";

export default function App() {
  const container = useRef<HTMLDivElement>(null);
  useMap(container);
  return <div ref={container} style={{ position: "absolute", inset: 0 }} />;
}
