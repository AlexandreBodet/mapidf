import type { Map as MlMap, GeoJSONSource } from "maplibre-gl";
import type { VehiclesResponse } from "../api/types";
import { whenStyleReady } from "./mapReady";

type V = VehiclesResponse["vehicles"][number];

interface Anim {
  from: [number, number];
  to: [number, number];
  bearing: number;
  start: number;
  vehicle: V;
}

export class VehicleLayer {
  private anims = new Map<string, Anim>();
  private raf = 0;
  private cancelReady: (() => void) | null = null;
  private selectedTripId: string | null = null;
  private follow = false;

  constructor(
    private map: MlMap,
    private durationMs: number,
  ) {
    this.ensureLayer();
  }

  setSelected(tripId: string | null) {
    this.selectedTripId = tripId;
  }

  setFollow(follow: boolean) {
    this.follow = follow;
  }

  private ensureLayer() {
    const add = () => {
      if (this.map.getSource("vehicles")) {
        return;
      }
      this.map.addSource("vehicles", { type: "geojson", data: this.featureCollection([]) });
      this.map.addLayer({
        id: "vehicles",
        type: "circle",
        source: "vehicles",
        paint: {
          "circle-radius": ["case", ["get", "selected"], 11, 7],
          "circle-color": ["case", ["==", ["get", "source"], "REALTIME"], "#e30613", "#f7a600"],
          "circle-stroke-color": ["case", ["get", "selected"], "#1d4ed8", "#fff"],
          "circle-stroke-width": ["case", ["get", "selected"], 4, 2],
          "circle-opacity": ["case", ["==", ["get", "source"], "INTERPOLATED"], 0.7, 1.0],
        },
      });
    };
    this.cancelReady = whenStyleReady(this.map, add);
  }

  private featureCollection(features: GeoJSON.Feature[]): GeoJSON.FeatureCollection {
    return { type: "FeatureCollection", features };
  }

  update(vehicles: V[], now: number) {
    const seen = new Set<string>();
    for (const vehicle of vehicles) {
      seen.add(vehicle.tripId);
      const prev = this.anims.get(vehicle.tripId);
      const current = prev ? this.pointAt(prev, now) : ([vehicle.lng, vehicle.lat] as [number, number]);
      this.anims.set(vehicle.tripId, {
        from: current,
        to: [vehicle.lng, vehicle.lat],
        bearing: vehicle.bearing,
        start: now,
        vehicle,
      });
    }
    for (const id of [...this.anims.keys()]) {
      if (!seen.has(id)) {
        this.anims.delete(id);
      }
    }
    this.startLoop();
  }

  private pointAt(anim: Anim, now: number): [number, number] {
    const t = Math.min(1, (now - anim.start) / this.durationMs);
    return [
      anim.from[0] + (anim.to[0] - anim.from[0]) * t,
      anim.from[1] + (anim.to[1] - anim.from[1]) * t,
    ];
  }

  private startLoop() {
    if (this.raf) {
      return;
    }
    const step = (now: number) => {
      const source = this.map.getSource("vehicles") as GeoJSONSource | undefined;
      if (source) {
        let followPoint: [number, number] | null = null;
        const features = [...this.anims.values()].map((anim) => {
          const [lng, lat] = this.pointAt(anim, now);
          const selected = anim.vehicle.tripId === this.selectedTripId;
          if (selected && this.follow) {
            followPoint = [lng, lat];
          }
          return {
            type: "Feature",
            properties: {
              tripId: anim.vehicle.tripId,
              source: anim.vehicle.source,
              bearing: anim.bearing,
              headsign: anim.vehicle.headsign,
              nextStop: anim.vehicle.nextStop,
              status: anim.vehicle.status,
              selected,
            },
            geometry: { type: "Point", coordinates: [lng, lat] },
          } as GeoJSON.Feature;
        });
        source.setData(this.featureCollection(features));
        if (followPoint) {
          this.map.jumpTo({ center: followPoint });
        }
      }
      this.raf = requestAnimationFrame(step);
    };
    this.raf = requestAnimationFrame(step);
  }

  destroy() {
    if (this.raf) {
      cancelAnimationFrame(this.raf);
    }
    this.raf = 0;
    if (this.cancelReady) {
      this.cancelReady();
      this.cancelReady = null;
    }
    this.anims.clear();
  }
}
