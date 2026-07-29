import { useEffect, useRef } from "react";
import type { Map as MlMap } from "maplibre-gl";
import type { NetworkResponse, VehiclesResponse } from "../api/types";
import { fetchVehicles } from "../api/network";
import { VEHICLE_POLL_MS } from "../api/config";
import { VehicleLayer } from "./VehicleLayer";

type V = VehiclesResponse["vehicles"][number];

// Rendu multi-lignes (couleur par véhicule selon sa ligne, atténuation par confiance) : tâche 14.
// Ici on ne fait que le minimum pour compiler après le passage à /vehicles (réseau entier, un
// seul poll) : tous les véhicules partagent provisoirement cette couleur neutre.
const PLACEHOLDER_VEHICLE_COLOR = "#666";

export function useVehicles(
  map: MlMap | null,
  network: NetworkResponse | null,
  selectedJourneyRef: string | null = null,
  follow = false,
  onSelected?: (vehicle: V | null) => void,
  onCount?: (n: number) => void,
  highlightedJourneyRefs: Set<string> = new Set(),
) {
  const layerRef = useRef<VehicleLayer | null>(null);
  // Véhicules du dernier poll : permet de remplir le panneau immédiatement à la sélection.
  const lastVehiclesRef = useRef<V[]>([]);
  // Refs pour que la boucle de poll lise toujours la dernière valeur sans se ré-abonner.
  const selectedRef = useRef(selectedJourneyRef);
  const onSelectedRef = useRef(onSelected);
  const onCountRef = useRef(onCount);
  selectedRef.current = selectedJourneyRef;
  onSelectedRef.current = onSelected;
  onCountRef.current = onCount;

  useEffect(() => {
    if (!map) {
      return;
    }
    const layer = new VehicleLayer(map, VEHICLE_POLL_MS, PLACEHOLDER_VEHICLE_COLOR);
    layerRef.current = layer;
    let cancelled = false;
    let timer: number;
    const tick = async () => {
      try {
        const response = await fetchVehicles();
        if (cancelled) {
          return;
        }
        lastVehiclesRef.current = response.vehicles;
        layer.update(response.vehicles, performance.now());
        onCountRef.current?.(response.vehicles.length);
        // Rafraîchit le panneau du train suivi avec la donnée fraîche de ce poll.
        const id = selectedRef.current;
        if (id) {
          onSelectedRef.current?.(response.vehicles.find((v) => v.journeyRef === id) ?? null);
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
  }, [map]);

  useEffect(() => {
    layerRef.current?.setSelected(selectedJourneyRef);
    // Remplit le panneau immédiatement depuis le dernier poll connu — sinon, après un clic
    // sur un passage (qui ne fournit qu'un journeyRef), la card n'apparaît qu'au tick suivant (~4 s).
    if (selectedJourneyRef) {
      onSelectedRef.current?.(
        lastVehiclesRef.current.find((v) => v.journeyRef === selectedJourneyRef) ?? null,
      );
    }
  }, [map, selectedJourneyRef]);

  useEffect(() => {
    layerRef.current?.setFollow(follow);
  }, [map, follow]);

  useEffect(() => {
    layerRef.current?.setHighlighted(highlightedJourneyRefs);
  }, [map, highlightedJourneyRefs]);
}
