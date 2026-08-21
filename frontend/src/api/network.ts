import { API_BASE } from "./config";
import type { NetworkResponse, VehiclesResponse, DeparturesResponse, DisruptionsResponse, StationSearchResponse } from "./types";

async function getJson<T>(path: string, signal?: AbortSignal): Promise<T> {
  const response = await fetch(`${API_BASE}${path}`, { signal });
  if (!response.ok) {
    throw new Error(`${path} ${response.status}`);
  }
  // `response.json()` rend `any`. L'assertion est écrite plutôt que subie : c'est **le** point où
  // la forme de l'API est affirmée sans être vérifiée, donc où un champ renommé côté serveur
  // deviendra un `undefined` trois couches plus loin au lieu d'une erreur ici. Validation à
  // l'exécution : cf. QUA-13.
  return (await response.json()) as T;
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

/** Perturbations en cours (le serveur écarte déjà les travaux à venir). */
export function fetchDisruptions(): Promise<DisruptionsResponse> {
  return getJson<DisruptionsResponse>("/disruptions");
}

export function searchStations(q: string, signal?: AbortSignal): Promise<StationSearchResponse> {
  return getJson<StationSearchResponse>(`/stations/search?q=${encodeURIComponent(q)}`, signal);
}
