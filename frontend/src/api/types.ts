export interface ShapeResponse {
  lineId: string;
  color: string;
  shape: [number, number][];
  stops: { id: string; name: string; lat: number; lng: number; platformIds: string[] }[];
}

export interface DeparturesResponse {
  stationName: string;
  directions: {
    destination: string;
    passages: { journeyRef: string; expectedTime: string; status: string }[];
  }[];
}

// Sous-ensemble d'un véhicule affiché dans le panneau de détail (sélection courante).
export interface VehicleSummary {
  headsign: string;
  nextStop: string;
  status: string;
  source: string;
  expectedTime: string;
}

export interface VehiclesResponse {
  asOf: string;
  vehicles: {
    tripId: string;
    lat: number;
    lng: number;
    bearing: number;
    status: string;
    headsign: string;
    nextStop: string;
    expectedTime: string;
    source: "REALTIME" | "INTERPOLATED";
  }[];
}
