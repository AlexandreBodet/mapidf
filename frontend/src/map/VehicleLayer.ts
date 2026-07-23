import type { Map as MlMap, GeoJSONSource } from "maplibre-gl";
import type { VehiclesResponse } from "../api/types";

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
  private loadHandler: (() => void) | null = null;

  constructor(
    private map: MlMap,
    private durationMs: number,
  ) {
    this.ensureLayer();
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
          "circle-radius": 7,
          "circle-color": ["case", ["==", ["get", "source"], "REALTIME"], "#e30613", "#f7a600"],
          "circle-stroke-color": "#fff",
          "circle-stroke-width": 2,
          "circle-opacity": ["case", ["==", ["get", "source"], "INTERPOLATED"], 0.7, 1.0],
        },
      });
    };
    if (this.map.isStyleLoaded()) {
      add();
    } else {
      this.loadHandler = add;
      this.map.once("load", add);
    }
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
        const features = [...this.anims.values()].map((anim) => {
          const [lng, lat] = this.pointAt(anim, now);
          return {
            type: "Feature",
            properties: {
              tripId: anim.vehicle.tripId,
              source: anim.vehicle.source,
              bearing: anim.bearing,
              headsign: anim.vehicle.headsign,
              nextStop: anim.vehicle.nextStop,
              status: anim.vehicle.status,
            },
            geometry: { type: "Point", coordinates: [lng, lat] },
          } as GeoJSON.Feature;
        });
        source.setData(this.featureCollection(features));
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
    if (this.loadHandler) {
      this.map.off("load", this.loadHandler);
      this.loadHandler = null;
    }
    this.anims.clear();
  }
}
