export interface NetworkLine {
  id: string;
  shortName: string;
  color: string;
  mode: string;
}

export interface NetworkShape {
  lineId: string;
  direction: number;
  terminusName: string;
  coordinates: [number, number][];
}

export interface NetworkStation {
  id: string;
  name: string;
  lat: number;
  lng: number;
  lineIds: string[];
}

export interface NetworkResponse {
  lines: NetworkLine[];
  shapes: NetworkShape[];
  stations: NetworkStation[];
}

export interface Vehicle {
  journeyRef: string;
  lineId: string;
  lat: number;
  lng: number;
  bearing: number;
  status: string;
  headsign: string;
  nextStop: string;
  expectedTime: string;
  /** Dernière mise à jour de la course côté SIRI. Information, pas critère d'atténuation. */
  recordedAt: string | null;
  /** APPROXIMATE = course à un seul appel SIRI (36 % du flux) : atténué, jamais masqué. */
  confidence: "RELIABLE" | "APPROXIMATE";
}

export interface VehiclesResponse {
  asOf: string;
  vehicles: Vehicle[];
}

export interface Passage {
  journeyRef: string;
  expectedTime: string;
  status: string;
}

export interface DeparturesResponse {
  stationName: string;
  lines: {
    lineId: string;
    shortName: string;
    color: string;
    directions: { destination: string; passages: Passage[] }[];
  }[];
}
