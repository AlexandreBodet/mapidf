import { useEffect, useRef } from "react";
import type { Map as MlMap } from "maplibre-gl";
import type { NetworkResponse, Vehicle } from "../api/types";
import { fetchVehicles } from "../api/network";
import { VEHICLE_POLL_MS } from "../api/config";
import { VehicleLayer } from "./VehicleLayer";

export function useVehicles(
  map: MlMap | null,
  network: NetworkResponse | null,
  selectedJourneyRef: string | null = null,
  follow = false,
  onSelected?: (vehicle: Vehicle | null) => void,
  onCounts?: (counts: Map<string, number>) => void,
  highlightedJourneyRefs: Set<string> = new Set(),
  visibleLines: Set<string> | null = null,
) {
  const layerRef = useRef<VehicleLayer | null>(null);
  // Véhicules du dernier poll, indexés par journeyRef : alimente les panneaux, puisque
  // headsign/nextStop/expectedTime/status ne sont plus posés sur les features GeoJSON
  // (allègement de la boucle de rendu, tâche 14).
  const vehiclesByRef = useRef<Map<string, Vehicle>>(new Map());
  // Refs pour que la boucle de poll lise toujours la dernière valeur sans se ré-abonner.
  const selectedRef = useRef(selectedJourneyRef);
  const onSelectedRef = useRef(onSelected);
  const onCountsRef = useRef(onCounts);
  selectedRef.current = selectedJourneyRef;
  onSelectedRef.current = onSelected;
  onCountsRef.current = onCounts;

  useEffect(() => {
    // La couche n'est créée qu'une fois le réseau connu : c'est lui qui fournit les
    // couleurs par ligne (colorByLine), nécessaires à la construction des icônes.
    if (!map || !network) {
      return;
    }
    const colorByLine = new Map(network.lines.map((line) => [line.id, line.color]));
    const layer = new VehicleLayer(map, VEHICLE_POLL_MS, colorByLine);
    layerRef.current = layer;
    let cancelled = false;
    let timer: number;
    const tick = async () => {
      try {
        const response = await fetchVehicles();
        if (cancelled) {
          return;
        }
        const byRef = new Map(response.vehicles.map((v) => [v.journeyRef, v]));
        vehiclesByRef.current = byRef;
        layer.update(response.vehicles, performance.now());
        const counts = new Map<string, number>();
        for (const vehicle of response.vehicles) {
          counts.set(vehicle.lineId, (counts.get(vehicle.lineId) ?? 0) + 1);
        }
        onCountsRef.current?.(counts);
        // Rafraîchit le panneau du train suivi avec la donnée fraîche de ce poll.
        const ref = selectedRef.current;
        if (ref) {
          onSelectedRef.current?.(byRef.get(ref) ?? null);
        }
      } catch {
        // on conserve l'affichage courant
      }
      if (cancelled) {
        return;
      }
      timer = window.setTimeout(tick, VEHICLE_POLL_MS);
    };
    tick();
    return () => {
      cancelled = true;
      window.clearTimeout(timer);
      layer.destroy();
      layerRef.current = null;
    };
  }, [map, network]);

  useEffect(() => {
    layerRef.current?.setSelected(selectedJourneyRef);
    // Remplit le panneau immédiatement depuis le dernier poll connu — sinon, après un clic
    // sur une flèche ou un passage (qui ne fournit qu'un journeyRef), la card n'apparaît
    // qu'au tick suivant (~4 s).
    if (selectedJourneyRef) {
      onSelectedRef.current?.(vehiclesByRef.current.get(selectedJourneyRef) ?? null);
    }
  }, [map, selectedJourneyRef]);

  useEffect(() => {
    layerRef.current?.setFollow(follow);
  }, [map, follow]);

  useEffect(() => {
    layerRef.current?.setHighlighted(highlightedJourneyRefs);
  }, [map, highlightedJourneyRefs]);

  useEffect(() => {
    layerRef.current?.setVisibleLines(visibleLines);
  }, [map, network, visibleLines]);
}
