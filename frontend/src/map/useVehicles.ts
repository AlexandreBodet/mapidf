import { useEffect, useRef } from "react";
import type { Map as MlMap } from "maplibre-gl";
import type { NetworkResponse, Vehicle } from "../api/types";
import { fetchVehicles } from "../api/network";
import { VEHICLE_POLL_MS } from "../api/config";
import { VehicleLayer } from "./VehicleLayer";

/**
 * Résultat d'un poll `/vehicles`. Un champ `null` veut dire « inconnu, garde ta dernière valeur » —
 * c'est le cas de tous sur échec, `failing` disant alors que la carte continue d'animer des
 * positions périmées.
 */
export interface PollOutcome {
  counts: Map<string, number> | null;
  /** Date de la donnée servie, affichée comme sa date de mise à jour. */
  asOf: string | null;
  failing: boolean;
  inService: boolean | null;
}

export function useVehicles(
  map: MlMap | null,
  network: NetworkResponse | null,
  selectedJourneyRef: string | null = null,
  follow = false,
  onSelected?: (vehicle: Vehicle | null) => void,
  onSnapshot?: (outcome: PollOutcome) => void,
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
  const onSnapshotRef = useRef(onSnapshot);
  selectedRef.current = selectedJourneyRef;
  onSelectedRef.current = onSelected;
  onSnapshotRef.current = onSnapshot;

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
        onSnapshotRef.current?.({
          counts, asOf: response.asOf, failing: false,
          // `?? null` : un backend qui n'a pas encore le champ ne doit pas passer pour éteint.
          inService: response.inService ?? null,
        });
        // Rafraîchit le panneau du train suivi avec la donnée fraîche de ce poll.
        const ref = selectedRef.current;
        if (ref) {
          onSelectedRef.current?.(byRef.get(ref) ?? null);
        }
      } catch {
        // On conserve l'affichage courant, mais on le signale : les flèches continuent de
        // s'animer vers leur dernière cible connue, ce qui ne se distingue pas d'un flux vivant.
        if (!cancelled) {
          // `inService` reste inconnu : le backend est injoignable, pas l'horloge du réseau.
          onSnapshotRef.current?.({ counts: null, asOf: null, failing: true, inService: null });
        }
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
