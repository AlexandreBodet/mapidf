export interface ShapeResponse {
  lineId: string;
  color: string;
  shape: [number, number][];
  stops: { id: string; name: string; lat: number; lng: number }[];
}

export interface VehiclesResponse {
  asOf: string;
  vehicles: {
    tripId: string;
    lat: number;
    lng: number;
    bearing: number;
    delaySec: number;
    headsign: string;
    nextStop: string;
    source: "REALTIME" | "INTERPOLATED";
  }[];
}
