import type { Map as MlMap, GeoJSONSource } from "maplibre-gl";
import type { VehiclesResponse } from "../api/types";
import { whenStyleReady } from "./mapReady";

// Au-delà de cette distance entre deux polls, ce n'est pas un déplacement réel de métro
// (~quelques dizaines de mètres par poll) mais une correction/flip de données : on place
// le train directement (snap) au lieu d'animer un glissement trompeur à travers la carte.
const SNAP_DISTANCE_M = 300;

// Distance approximative entre deux [lng, lat] en mètres (équirectangulaire avec cos(lat)),
// suffisante à l'échelle d'une ligne parisienne.
function distanceMeters(a: [number, number], b: [number, number]): number {
  const R = 6371000;
  const rad = Math.PI / 180;
  const dLat = (b[1] - a[1]) * rad;
  const dLng = (b[0] - a[0]) * rad;
  const meanLat = ((a[1] + b[1]) / 2) * rad;
  const x = dLng * Math.cos(meanLat);
  return R * Math.sqrt(x * x + dLat * dLat);
}

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
  private highlightedTripIds: Set<string> = new Set();
  private moveHandler: (() => void) | null = null;

  constructor(
    private map: MlMap,
    private durationMs: number,
    private color: string,
  ) {
    this.ensureLayer();
  }

  setSelected(tripId: string | null) {
    this.selectedTripId = tripId;
  }

  setFollow(follow: boolean) {
    this.follow = follow;
  }

  setHighlighted(ids: Set<string>) {
    this.highlightedTripIds = ids;
  }

  setColor(color: string) {
    this.color = color;
    // L'icône est déjà sur la carte : on remplace son contenu par une flèche à la
    // nouvelle couleur (la couche symbol la ré-affiche automatiquement). Si l'image
    // n'existe pas encore, ensureLayer l'ajoutera avec this.color mis à jour.
    if (this.map.hasImage("vehicle-arrow")) {
      this.map.updateImage("vehicle-arrow", this.arrowImage());
    }
  }

  private arrowImage(): ImageData {
    const size = 24;
    const canvas = document.createElement("canvas");
    canvas.width = size;
    canvas.height = size;
    const ctx = canvas.getContext("2d")!;
    ctx.fillStyle = this.color;
    ctx.strokeStyle = "#fff";
    ctx.lineWidth = 2;
    ctx.beginPath();
    ctx.moveTo(size / 2, 2);        // pointe (haut = nord)
    ctx.lineTo(size - 4, size - 4); // bas droite
    ctx.lineTo(4, size - 4);        // bas gauche
    ctx.closePath();
    ctx.fill();
    ctx.stroke();
    return ctx.getImageData(0, 0, size, size);
  }

  private ensureLayer() {
    const add = () => {
      if (this.map.getSource("vehicles")) {
        return;
      }
      this.map.addSource("vehicles", { type: "geojson", data: this.featureCollection([]) });
      if (!this.map.hasImage("vehicle-arrow")) {
        this.map.addImage("vehicle-arrow", this.arrowImage());
      }
      // Halo de sélection SOUS les flèches : anneau bleu, uniquement la feature sélectionnée.
      this.map.addLayer({
        id: "vehicles-halo",
        type: "circle",
        source: "vehicles",
        filter: ["==", ["get", "selected"], true],
        paint: {
          "circle-radius": 12,
          "circle-color": "rgba(29,78,216,0.15)",
          "circle-stroke-color": "#1d4ed8",
          "circle-stroke-width": 3,
        },
      });
      // Anneau sur les véhicules concernés par les passages de l'arrêt ouvert (distinct
      // du halo bleu de sélection). Filtré sur la propriété `highlighted` des features.
      this.map.addLayer({
        id: "vehicles-highlight",
        type: "circle",
        source: "vehicles",
        filter: ["==", ["get", "highlighted"], true],
        paint: {
          "circle-radius": 11,
          "circle-color": "rgba(0,0,0,0)",
          "circle-stroke-color": "#111",
          "circle-stroke-width": 2.5,
        },
      });
      // Flèches orientées sur le bearing (0 = nord), alignées à la carte.
      this.map.addLayer({
        id: "vehicles",
        type: "symbol",
        source: "vehicles",
        layout: {
          "icon-image": "vehicle-arrow",
          "icon-rotate": ["get", "bearing"],
          "icon-rotation-alignment": "map",
          "icon-allow-overlap": true,
          "icon-size": ["interpolate", ["linear"], ["zoom"], 10, 0.5, 13, 0.85, 16, 1.5],
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
      const target: [number, number] = [vehicle.lng, vehicle.lat];
      // Saut invraisemblable → snap (pas d'animation) : from = target. Sinon, tween normal.
      const from = distanceMeters(current, target) > SNAP_DISTANCE_M ? target : current;
      this.anims.set(vehicle.tripId, {
        from,
        to: target,
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
          const highlighted = this.highlightedTripIds.has(anim.vehicle.tripId);
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
              expectedTime: anim.vehicle.expectedTime,
              status: anim.vehicle.status,
              selected,
              highlighted,
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
    if (this.moveHandler) {
      this.map.off("move", this.moveHandler);
      this.moveHandler = null;
    }
    for (const id of ["vehicles", "vehicles-halo", "vehicles-highlight"]) {
      if (this.map.getLayer(id)) {
        this.map.removeLayer(id);
      }
    }
    if (this.map.getSource("vehicles")) {
      this.map.removeSource("vehicles");
    }
    if (this.map.hasImage("vehicle-arrow")) {
      this.map.removeImage("vehicle-arrow");
    }
    this.anims.clear();
  }
}
