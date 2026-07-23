import { useEffect } from "react";
import type { Map as MlMap } from "maplibre-gl";
import { fetchVehicles } from "../api/lines";
import { VEHICLE_POLL_MS } from "../api/config";
import { VehicleLayer } from "./VehicleLayer";

export function useVehicles(map: MlMap | null, lineId: string) {
  useEffect(() => {
    if (!map) {
      return;
    }
    const layer = new VehicleLayer(map, VEHICLE_POLL_MS);
    let cancelled = false;
    let timer: number;
    const tick = async () => {
      try {
        const response = await fetchVehicles(lineId);
        if (cancelled) {
          return;
        }
        layer.update(response.vehicles, performance.now());
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
    };
  }, [map, lineId]);
}
