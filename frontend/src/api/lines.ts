import { API_BASE } from "./config";
import type { ShapeResponse, VehiclesResponse, DeparturesResponse } from "./types";

export async function fetchShape(lineId: string): Promise<ShapeResponse> {
  const response = await fetch(`${API_BASE}/lines/${lineId}/shape`);
  if (!response.ok) {
    throw new Error(`shape ${response.status}`);
  }
  return response.json();
}

export async function fetchVehicles(lineId: string): Promise<VehiclesResponse> {
  const response = await fetch(`${API_BASE}/lines/${lineId}/vehicles`);
  if (!response.ok) {
    throw new Error(`vehicles ${response.status}`);
  }
  return response.json();
}

export async function fetchDepartures(
  lineId: string,
  stationId: string,
  signal?: AbortSignal,
): Promise<DeparturesResponse> {
  const response = await fetch(`${API_BASE}/lines/${lineId}/stations/${encodeURIComponent(stationId)}/departures`, {
    signal,
  });
  if (!response.ok) {
    throw new Error(`departures ${response.status}`);
  }
  return response.json();
}
