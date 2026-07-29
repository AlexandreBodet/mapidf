import { useEffect, useMemo, useRef, useState } from "react";
import maplibregl from "maplibre-gl";
import { useMap } from "./map/MapView";
import { useNetwork } from "./map/useNetwork";
import { useVehicles } from "./map/useVehicles";
import { VehiclePanel } from "./ui/VehiclePanel";
import { StopPanel } from "./ui/StopPanel";
import { Legend } from "./ui/Legend";
import { fetchDepartures } from "./api/network";
import { VEHICLE_POLL_MS } from "./api/config";
import type { DeparturesResponse, Vehicle } from "./api/types";

type Selected = Vehicle | null;

export default function App() {
  const container = useRef<HTMLDivElement>(null);
  const map = useMap(container);
  const departuresAbort = useRef<AbortController | null>(null);
  const [selected, setSelected] = useState<Selected>(null);
  const [selectedJourneyRef, setSelectedJourneyRef] = useState<string | null>(null);
  const [follow, setFollow] = useState(false);
  const [station, setStation] = useState<DeparturesResponse | null>(null);
  const [selectedStationId, setSelectedStationId] = useState<string | null>(null);
  const [count, setCount] = useState(0);
  // Trains concernés par les passages de la station ouverte (surlignés sur la carte).
  // Une correspondance groupe plusieurs lignes (task 12) : on aplatit lignes puis directions.
  const highlightedJourneyRefs = useMemo(
    () =>
      new Set(
        station?.lines.flatMap((l) => l.directions).flatMap((d) => d.passages.map((p) => p.journeyRef)) ?? [],
      ),
    [station],
  );
  const network = useNetwork(map);
  // À chaque poll, rafraîchit le panneau avec la donnée fraîche du train suivi
  // (prochain arrêt + ETA vivants). Si le train quitte le flux, on garde le dernier état connu.
  useVehicles(map, network, selectedJourneyRef, follow, (v) => {
    if (v) {
      setSelected(v);
    }
  }, setCount, highlightedJourneyRefs);

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
      setSelectedJourneyRef(props.journeyRef as string);
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
      setSelectedJourneyRef(null);
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
        const fresh = await fetchDepartures(id, controller.signal);
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
        const fresh = await fetchDepartures(selectedStationId, controller.signal);
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
    setSelectedJourneyRef(null);
    setFollow(false);
  };

  const closeStation = () => {
    departuresAbort.current?.abort();
    departuresAbort.current = null;
    setStation(null);
    setSelectedStationId(null);
    map?.setFilter("stops-selected", ["==", ["get", "id"], "__none__"]);
  };

  const followTrainFromPanel = (journeyRef: string) => {
    closeStation();
    setSelected(null);
    setSelectedJourneyRef(journeyRef);
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
      {/* Sélecteur de lignes, panneaux détaillés et filtre : tâche 15. Compteur total en attendant. */}
      <Legend color="#666" count={count} />
    </>
  );
}
