import type { Map as MlMap, GeoJSONSource } from "maplibre-gl";
import type { VehiclesResponse } from "../api/types";
import { whenStyleReady } from "./mapReady";

// Au-delà de cette distance entre deux polls, ce n'est pas un déplacement réel de métro
// (~quelques dizaines de mètres par poll) mais une correction/flip de données : on place
// le train directement (snap) au lieu d'animer un glissement trompeur à travers la carte.
const SNAP_DISTANCE_M = 300;

// Le métro est lent : reconstruire la source ~15 fps au lieu de 60 suffit visuellement
// et divise d'autant le coût de setData (goulot à l'échelle réseau).
const RENDER_INTERVAL_MS = 66;

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

/**
 * Propriétés réellement posées sur chaque feature GeoJSON par `update()`/`render()` : c'est
 * tout ce qu'un clic direct sur une flèche peut lire, avant que le prochain poll (~4 s)
 * ne fournisse le `Vehicle` complet via la `Map` tenue par `useVehicles`. headsign, nextStop,
 * expectedTime, status et recordedAt ne servent qu'au clic et ne sont donc plus émis ici —
 * les recopier par frame et par véhicule (705 × 15/s) coûtait trois chaînes inutiles.
 */
export interface VehicleFeatureProperties {
  journeyRef: string;
  lineId: string;
  bearing: number;
  /** Identifiant d'image MapLibre (une par couleur de ligne distincte). */
  icon: string;
  /** Course à un seul appel SIRI (36 % du flux) : atténuée en icon-opacity, jamais masquée. */
  approximate: boolean;
}

interface Anim {
  from: [number, number];
  to: [number, number];
  bearing: number;
  start: number;
  vehicle: V;
  /** Feature réutilisée d'une frame à l'autre : on ne mute que ses coordonnées. */
  feature: GeoJSON.Feature<GeoJSON.Point>;
}

export class VehicleLayer {
  private anims = new Map<string, Anim>();
  private raf = 0;
  private lastRenderAt = 0;
  private cancelReady: (() => void) | null = null;
  private selectedJourneyRef: string | null = null;
  private follow = false;
  private highlightedJourneyRefs: Set<string> = new Set();
  private moveHandler: (() => void) | null = null;
  // Tableau réutilisé : à 705 véhicules et 15 fps, réallouer une liste et 705 objets par
  // frame génère une pression GC inutile.
  private rendered: GeoJSON.Feature[] = [];
  private visibleLines: Set<string> | null = null;
  // Images `vehicle-arrow-*` créées à la volée (une par couleur de ligne distincte), pour
  // pouvoir toutes les nettoyer au démontage.
  private imageIds = new Set<string>();

  constructor(
    private map: MlMap,
    private durationMs: number,
    private colorByLine: Map<string, string>,
  ) {
    this.ensureLayer();
  }

  private applySelectionState() {
    if (!this.map.getSource("vehicles")) {
      return;
    }
    this.map.removeFeatureState({ source: "vehicles" });
    if (this.selectedJourneyRef) {
      this.map.setFeatureState({ source: "vehicles", id: this.selectedJourneyRef }, { selected: true });
    }
    for (const id of this.highlightedJourneyRefs) {
      this.map.setFeatureState({ source: "vehicles", id }, { highlighted: true });
    }
  }

  setSelected(journeyRef: string | null) {
    this.selectedJourneyRef = journeyRef;
    this.applySelectionState();
  }

  setFollow(follow: boolean) {
    this.follow = follow;
    if (follow) {
      this.startLoop();
    }
  }

  setHighlighted(ids: Set<string>) {
    this.highlightedJourneyRefs = ids;
    this.applySelectionState();
  }

  /** Filtre client par ligne : aucun appel réseau, on cesse simplement d'émettre les features. */
  setVisibleLines(lineIds: Set<string> | null) {
    this.visibleLines = lineIds;
    this.render(performance.now());
  }

  /** Identifiant d'image MapLibre pour une couleur donnée (14 couleurs distinctes au métro). */
  private iconIdFor(color: string): string {
    const id = `vehicle-arrow-${color.replace("#", "")}`;
    if (!this.map.hasImage(id)) {
      this.map.addImage(id, this.arrowImage(color));
      this.imageIds.add(id);
    }
    return id;
  }

  private arrowImage(color: string): ImageData {
    const size = 24;
    const canvas = document.createElement("canvas");
    canvas.width = size;
    canvas.height = size;
    const ctx = canvas.getContext("2d")!;
    ctx.fillStyle = color;
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
      this.map.addSource("vehicles", {
        type: "geojson",
        promoteId: "journeyRef",
        data: this.featureCollection([]),
      });
      // Halo de sélection SOUS les flèches : anneau bleu, uniquement la feature sélectionnée.
      // Couche permanente (les filtres ne lisent pas feature-state) : visibilité pilotée
      // par l'opacité, via feature-state "selected".
      this.map.addLayer({
        id: "vehicles-halo",
        type: "circle",
        source: "vehicles",
        paint: {
          "circle-radius": 12,
          "circle-color": "rgba(29,78,216,0.15)",
          "circle-stroke-color": "#1d4ed8",
          "circle-stroke-width": 3,
          "circle-opacity": ["case", ["boolean", ["feature-state", "selected"], false], 1, 0],
          "circle-stroke-opacity": ["case", ["boolean", ["feature-state", "selected"], false], 1, 0],
        },
      });
      // Anneau sur les véhicules concernés par les passages de l'arrêt ouvert (distinct
      // du halo bleu de sélection). Couche permanente pilotée par feature-state "highlighted".
      this.map.addLayer({
        id: "vehicles-highlight",
        type: "circle",
        source: "vehicles",
        paint: {
          "circle-radius": 11,
          "circle-color": "rgba(0,0,0,0)",
          "circle-stroke-color": "#111",
          "circle-stroke-width": 2.5,
          "circle-stroke-opacity": ["case", ["boolean", ["feature-state", "highlighted"], false], 1, 0],
        },
      });
      // Flèches orientées sur le bearing (0 = nord), alignées à la carte, une image par
      // couleur de ligne (icon-image piloté par la feature, pas de icon-color sur SDF : on
      // garde le liseré blanc qui rend les flèches lisibles sur le fond de carte).
      this.map.addLayer({
        id: "vehicles",
        type: "symbol",
        source: "vehicles",
        layout: {
          "icon-image": ["get", "icon"],
          "icon-rotate": ["get", "bearing"],
          "icon-rotation-alignment": "map",
          "icon-allow-overlap": true,
          "icon-size": ["interpolate", ["linear"], ["zoom"], 10, 0.5, 13, 0.85, 16, 1.5],
        },
        paint: {
          // APPROXIMATE = course à un seul appel SIRI (36 % du flux mesuré) : le train est
          // borné à l'arrêt précédant son unique appel, souvent un terminus lointain. Atténué,
          // jamais masqué — un train perturbé doit rester visible.
          "icon-opacity": ["case", ["get", "approximate"], 0.45, 1],
        },
      });
      this.applySelectionState();
      this.moveHandler = () => {
        if (this.raf) {
          return; // la boucle rend déjà
        }
        const now = performance.now();
        if (now - this.lastRenderAt < RENDER_INTERVAL_MS) {
          return; // throttle
        }
        this.lastRenderAt = now;
        this.render(now);
      };
      this.map.on("move", this.moveHandler);
    };
    this.cancelReady = whenStyleReady(this.map, add);
  }

  private featureCollection(features: GeoJSON.Feature[]): GeoJSON.FeatureCollection {
    return { type: "FeatureCollection", features };
  }

  update(vehicles: V[], now: number) {
    const seen = new Set<string>();
    for (const vehicle of vehicles) {
      if (!Number.isFinite(vehicle.lng) || !Number.isFinite(vehicle.lat) || !Number.isFinite(vehicle.bearing)) {
        continue; // position/orientation invalide → on n'anime pas une géométrie NaN
      }
      seen.add(vehicle.journeyRef);
      const prev = this.anims.get(vehicle.journeyRef);
      const current = prev ? this.pointAt(prev, now) : ([vehicle.lng, vehicle.lat] as [number, number]);
      const target: [number, number] = [vehicle.lng, vehicle.lat];
      // Saut invraisemblable → snap (pas d'animation) : from = target. Sinon, tween normal.
      const from = distanceMeters(current, target) > SNAP_DISTANCE_M ? target : current;
      const icon = this.iconIdFor(this.colorByLine.get(vehicle.lineId) ?? "#666666");
      const approximate = vehicle.confidence === "APPROXIMATE";
      if (prev) {
        // Anim existante : on ne touche pas prev.feature en géométrie (render() s'en charge
        // chaque frame), seulement ses propriétés variables.
        prev.from = from;
        prev.to = target;
        prev.bearing = vehicle.bearing;
        prev.start = now;
        prev.vehicle = vehicle;
        prev.feature.properties!.bearing = vehicle.bearing;
        prev.feature.properties!.icon = icon;
        prev.feature.properties!.approximate = approximate;
      } else {
        const feature: GeoJSON.Feature<GeoJSON.Point> = {
          type: "Feature",
          // Seules les propriétés qui servent au RENDU. headsign, nextStop, expectedTime,
          // status et recordedAt ne servent qu'au clic : useVehicles les garde dans une Map,
          // ce qui évite de recopier trois chaînes par véhicule et par frame (705 × 15/s).
          properties: {
            journeyRef: vehicle.journeyRef,
            lineId: vehicle.lineId,
            bearing: vehicle.bearing,
            icon,
            approximate,
          } satisfies VehicleFeatureProperties,
          geometry: { type: "Point", coordinates: [vehicle.lng, vehicle.lat] },
        };
        this.anims.set(vehicle.journeyRef, {
          from,
          to: target,
          bearing: vehicle.bearing,
          start: now,
          vehicle,
          feature,
        });
      }
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

  private isAnimating(now: number): boolean {
    for (const anim of this.anims.values()) {
      if (now - anim.start < this.durationMs) {
        return true;
      }
    }
    return false;
  }

  private render(now: number) {
    const source = this.map.getSource("vehicles") as GeoJSONSource | undefined;
    if (!source) {
      return;
    }
    // Culling : on n'envoie que les véhicules du viewport élargi (marge 20 %). Les anims
    // restent maintenues pour tous → le tween survit à une sortie/entrée d'écran.
    const bounds = this.map.getBounds();
    const west = bounds.getWest();
    const east = bounds.getEast();
    const south = bounds.getSouth();
    const north = bounds.getNorth();
    const padX = (east - west) * 0.2;
    const padY = (north - south) * 0.2;

    let followPoint: [number, number] | null = null;
    this.rendered.length = 0;
    for (const anim of this.anims.values()) {
      const [lng, lat] = this.pointAt(anim, now);
      if (anim.vehicle.journeyRef === this.selectedJourneyRef && this.follow) {
        followPoint = [lng, lat];
      }
      if (this.visibleLines && !this.visibleLines.has(anim.vehicle.lineId)) {
        continue;
      }
      if (lng < west - padX || lng > east + padX || lat < south - padY || lat > north + padY) {
        continue;
      }
      anim.feature.geometry.coordinates[0] = lng;
      anim.feature.geometry.coordinates[1] = lat;
      this.rendered.push(anim.feature);
    }
    source.setData({ type: "FeatureCollection", features: this.rendered });
    // Pas d'applySelectionState() ici : feature-state est stocké séparément du GeoJSON,
    // par id promu ("journeyRef"), et survit à setData ainsi qu'au culling (une feature qui
    // sort puis revient dans le viewport garde son état). Le réappliquer à chaque frame
    // serait un travail redondant ; il n'est fait que dans setSelected/setHighlighted et
    // à la fin de ensureLayer's add().
    if (followPoint) {
      this.map.jumpTo({ center: followPoint });
    }
  }

  private startLoop() {
    if (this.raf) {
      return;
    }
    const step = (now: number) => {
      if (now - this.lastRenderAt >= RENDER_INTERVAL_MS) {
        this.render(now);
        this.lastRenderAt = now;
      }
      if (this.isAnimating(now)) {
        this.raf = requestAnimationFrame(step);
      } else {
        // Plus rien à animer : rendu final puis arrêt de la boucle (CPU au repos).
        this.raf = 0;
        this.render(now);
      }
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
    for (const id of this.imageIds) {
      if (this.map.hasImage(id)) {
        this.map.removeImage(id);
      }
    }
    this.imageIds.clear();
    this.anims.clear();
  }
}
