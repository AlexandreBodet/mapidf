import { useEffect, useRef } from "react";
import type { Map as MlMap } from "maplibre-gl";
import type { VehiclesResponse } from "../api/types";
import { fetchVehicles } from "../api/lines";
import { VEHICLE_POLL_MS } from "../api/config";
import { VehicleLayer } from "./VehicleLayer";

type V = VehiclesResponse["vehicles"][number];

export function useVehicles(
  map: MlMap | null,
  lineId: string,
  color: string,
  selectedTripId: string | null = null,
  follow = false,
  onSelected?: (vehicle: V | null) => void,
  onCount?: (n: number) => void,
  highlightedTripIds: Set<string> = new Set(),
) {
  const layerRef = useRef<VehicleLayer | null>(null);
  // Refs pour que la boucle de poll lise toujours la dernière valeur sans se ré-abonner.
  const selectedRef = useRef(selectedTripId);
  const onSelectedRef = useRef(onSelected);
  const onCountRef = useRef(onCount);
  selectedRef.current = selectedTripId;
  onSelectedRef.current = onSelected;
  onCountRef.current = onCount;

  useEffect(() => {
    if (!map) {
      return;
    }
    const layer = new VehicleLayer(map, VEHICLE_POLL_MS, color);
    layerRef.current = layer;
    let cancelled = false;
    let timer: number;
    const tick = async () => {
      try {
        const response = await fetchVehicles(lineId);
        if (cancelled) {
          return;
        }
        layer.update(response.vehicles, performance.now());
        onCountRef.current?.(response.vehicles.length);
        // Rafraîchit le panneau du train suivi avec la donnée fraîche de ce poll.
        const id = selectedRef.current;
        if (id) {
          onSelectedRef.current?.(response.vehicles.find((v) => v.tripId === id) ?? null);
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
  }, [map, lineId]);

  useEffect(() => {
    layerRef.current?.setSelected(selectedTripId);
  }, [map, lineId, selectedTripId]);

  useEffect(() => {
    layerRef.current?.setFollow(follow);
  }, [map, lineId, follow]);

  useEffect(() => {
    layerRef.current?.setColor(color);
  }, [map, lineId, color]);

  useEffect(() => {
    layerRef.current?.setHighlighted(highlightedTripIds);
  }, [map, lineId, highlightedTripIds]);
}
