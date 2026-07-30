import { API_BASE } from "./config";
import type { NetworkResponse, VehiclesResponse, DeparturesResponse } from "./types";

async function getJson<T>(path: string, signal?: AbortSignal): Promise<T> {
  const response = await fetch(`${API_BASE}${path}`, { signal });
  if (!response.ok) {
    throw new Error(`${path} ${response.status}`);
  }
  return response.json();
}

/** Tout le réseau statique en un appel (37 polylignes, 321 stations), cacheable 10 min. */
export function fetchNetwork(): Promise<NetworkResponse> {
  return getJson<NetworkResponse>("/network");
}

/** Tous les véhicules du réseau suivi : un seul poll toutes les 4 s, pas un par ligne. */
export function fetchVehicles(): Promise<VehiclesResponse> {
  return getJson<VehiclesResponse>("/vehicles");
}

export function fetchDepartures(stationId: string, signal?: AbortSignal): Promise<DeparturesResponse> {
  return getJson<DeparturesResponse>(`/stations/${encodeURIComponent(stationId)}/departures`, signal);
}
