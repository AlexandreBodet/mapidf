import type { Map as MlMap, GeoJSONSource, FilterSpecification } from "maplibre-gl";
import type { VehiclesResponse } from "../api/types";
import { whenStyleReady } from "./mapReady";

// Au-delà de cette distance entre deux polls, ce n'est pas un déplacement réel de métro
// (~quelques dizaines de mètres par poll) mais une correction/flip de données : on place
// le train directement (snap) au lieu d'animer un glissement trompeur à travers la carte.
const SNAP_DISTANCE_M = 300;

// Le métro est lent : reconstruire la source ~15 fps au lieu de 60 suffit visuellement
// et divise d'autant le coût de setData (goulot à l'échelle réseau).
const RENDER_INTERVAL_MS = 66;

// Échelle de zoom voulue (retour utilisateur : au dézoom, 705 flèches formaient un tas
// illisible — les trains doivent disparaître AVANT les stations) :
//   zoom 10  → tracés seuls
//   zoom 11  → tracés + stations
//   zoom 12+ → + trains  ← ouverture (cf. `zoom` initial dans MapView.tsx)
//   zoom 13+ → + noms de stations (`stops-labels`, useNetwork.ts)
// Les noms ne partagent PLUS le seuil des trains : à 12, la collision MapLibre en écartait la
// majorité (un nom médian couvre ~2 100 m au sol pour 400 m entre stations voisines).
// Partagée par les trois couches (vehicles, vehicles-halo, vehicles-highlight) : un halo ou
// un anneau sans sa flèche serait pire que rien.
const MIN_VEHICLE_ZOOM = 12;

/**
 * Pourquoi les deux anneaux (`vehicles-halo`, `vehicles-highlight`) sont pilotés par
 * `setFilter` sur la propriété `journeyRef`, et NON par `feature-state` — qui est pourtant la
 * façon idiomatique de faire, et a déjà été essayée deux fois ici. Ne pas réintroduire sans
 * relire ceci.
 *
 * `render()` appelle `setData` ~15 fois par seconde sur ~705 features. Chaque `setData`
 * déclenche un aller-retour worker puis, au retour, `SourceCache.reload()` →
 * `_reloadTile` → `_tileLoaded`, qui appelle `SourceFeatureState.initializeTileState` →
 * `Tile.setFeatureState` → `bucket.update` → `ProgramConfiguration.updatePaintArrays`. Ce
 * dernier relit la tuile par index, `vtLayer.feature(pos.index)`, et lève
 * « feature index out of bounds » dès que les `buckets` et le `rawTileData` d'une tuile ne
 * viennent pas du même chargement. À cette cadence la fenêtre est constamment ouverte.
 *
 * Un filtre, lui, est évalué côté worker pendant la construction du bucket : la feature
 * écartée n'entre jamais dans la tuile, et aucun tableau de peinture n'est réindexé après
 * coup. Tant qu'aucun `setFeatureState` n'est appelé, deux garde-fous ferment le chemin qui
 * lève : `Tile.setFeatureState` sort immédiatement (`Object.keys(states).length === 0`,
 * l'état de la source restant `{}`), et `bucket.update` sort aussi
 * (`!this.stateDependentLayers.length`, plus aucune propriété de peinture ne lisant
 * `feature-state`). Contrepartie assumée : `setFilter` provoque lui-même un rechargement de
 * la source (`Style._updateLayer` marque `_updatedSources = 'reload'`), donc il ne doit être
 * appelé qu'au changement de sélection ou de surlignage — jamais par frame.
 */
function journeyRefFilter(ids: readonly string[]): FilterSpecification {
  // Liste vide = aucune feature ne correspond = zéro cercle dessiné. C'est le comportement
  // voulu quand rien n'est sélectionné, et il ne lève pas.
  return ["in", ["get", "journeyRef"], ["literal", [...ids]]];
}

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
  // Emprise élargie recalculée par chaque render() : critère de culling.
  private view = { west: 0, east: 0, south: 0, north: 0, padX: 0, padY: 0 };

  constructor(
    private map: MlMap,
    private durationMs: number,
    private colorByLine: Map<string, string>,
  ) {
    this.ensureLayer();
  }

  /** Critère de culling appliqué par render() sur l'emprise élargie du dernier calcul. */
  private inView(lng: number, lat: number): boolean {
    const v = this.view;
    return lng >= v.west - v.padX && lng <= v.east + v.padX
      && lat >= v.south - v.padY && lat <= v.north + v.padY;
  }

  /**
   * Pose le filtre du halo bleu. Aucun besoin de vérifier que le train est bien dans la source :
   * si le culling ou le filtre par ligne l'ont écarté, le filtre ne correspond à rien et zéro
   * cercle est dessiné ; quand le train rentre dans le viewport, sa feature réapparaît dans le
   * setData suivant et le halo revient sans aucune intervention.
   *
   * Sans-effet si la couche n'existe pas encore (style pas analysé) : `ensureLayer` repose les
   * deux filtres juste après l'avoir créée.
   */
  private applyHaloFilter() {
    if (!this.map.getLayer("vehicles-halo")) {
      return;
    }
    const ids = this.selectedJourneyRef ? [this.selectedJourneyRef] : [];
    this.map.setFilter("vehicles-halo", journeyRefFilter(ids));
  }

  /**
   * Pose le filtre de l'anneau noir des passages de la station ouverte. Les ids sont TRIÉS
   * volontairement : `Style.setFilter` sort tôt sur `deepEqual(layer.filter, filter)`, ce qui
   * évite un rechargement de la source à chaque rafraîchissement du panneau (~4 s) — mais
   * seulement si le filtre est identique terme à terme, or l'ordre d'itération d'un `Set` suit
   * l'ordre d'insertion, qui varie d'un poll à l'autre.
   */
  private applyHighlightFilter() {
    if (!this.map.getLayer("vehicles-highlight")) {
      return;
    }
    const ids = [...this.highlightedJourneyRefs].sort();
    this.map.setFilter("vehicles-highlight", journeyRefFilter(ids));
  }

  setSelected(journeyRef: string | null) {
    this.selectedJourneyRef = journeyRef;
    // Le halo est posé tout de suite : un filtre n'attend pas de render.
    this.applyHaloFilter();
    // La boucle est relancée pour le recentrage : render() est le seul endroit qui lit
    // `follow` et appelle jumpTo, et il peut être à l'arrêt (idle) au moment du clic.
    this.startLoop();
  }

  setFollow(follow: boolean) {
    this.follow = follow;
    if (follow) {
      this.startLoop();
    }
  }

  setHighlighted(ids: Set<string>) {
    this.highlightedJourneyRefs = ids;
    // Appelé à chaque rafraîchissement du panneau station (~4 s) avec un Set neuf mais souvent
    // de contenu identique : la comparaison est déléguée à Style.setFilter (cf. le tri dans
    // applyHighlightFilter). Pas de startLoop : l'anneau ne dépend plus d'un render.
    this.applyHighlightFilter();
  }

  /** Filtre client par ligne : aucun appel réseau, on cesse simplement d'émettre les features. */
  setVisibleLines(lineIds: Set<string> | null) {
    this.visibleLines = lineIds;
    // lastRenderAt doit suivre : sans lui, la boucle et le handler `move` croient qu'aucun rendu
    // n'a eu lieu et en refont un immédiatement — le throttle se décale d'une frame.
    const now = performance.now();
    this.lastRenderAt = now;
    this.render(now);
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
      // Pas de `promoteId` : il ne servait qu'à donner un id de feature au feature-state, dont
      // plus personne ne dépend ici (les filtres lisent la propriété `journeyRef`, et aucun
      // `feature.id` n'est lu au clic). Il coûtait en plus, à chaque setData, un Set puis une
      // Map de ~705 entrées construits côté worker par `isUpdateableGeoJSON`/`toUpdateable`
      // pour préparer un `updateData` diff que nous n'utilisons pas.
      this.map.addSource("vehicles", {
        type: "geojson",
        data: this.featureCollection([]),
      });
      // Halo de sélection SOUS les flèches : anneau bleu sur le seul train suivi. Opacités
      // constantes (valeurs par défaut à 1) — c'est le FILTRE, posé par applyHaloFilter, qui
      // décide ce qui est dessiné. Voir l'en-tête de journeyRefFilter pour le pourquoi.
      this.map.addLayer({
        id: "vehicles-halo",
        type: "circle",
        source: "vehicles",
        minzoom: MIN_VEHICLE_ZOOM,
        filter: journeyRefFilter([]),
        paint: {
          "circle-radius": 12,
          "circle-color": "rgba(29,78,216,0.15)",
          "circle-stroke-color": "#1d4ed8",
          "circle-stroke-width": 3,
        },
      });
      // Anneau sur les véhicules concernés par les passages de l'arrêt ouvert (distinct du halo
      // bleu de sélection). Couche séparée, donc un train suivi ET surligné porte bien les deux
      // anneaux : chaque filtre est indépendant de l'autre.
      this.map.addLayer({
        id: "vehicles-highlight",
        type: "circle",
        source: "vehicles",
        minzoom: MIN_VEHICLE_ZOOM,
        filter: journeyRefFilter([]),
        paint: {
          "circle-radius": 11,
          "circle-color": "rgba(0,0,0,0)",
          "circle-stroke-color": "#111",
          "circle-stroke-width": 2.5,
        },
      });
      // Flèches orientées sur le bearing (0 = nord), alignées à la carte, une image par
      // couleur de ligne (icon-image piloté par la feature, pas de icon-color sur SDF : on
      // garde le liseré blanc qui rend les flèches lisibles sur le fond de carte).
      this.map.addLayer({
        id: "vehicles",
        type: "symbol",
        source: "vehicles",
        minzoom: MIN_VEHICLE_ZOOM,
        layout: {
          "icon-image": ["get", "icon"],
          "icon-rotate": ["get", "bearing"],
          "icon-rotation-alignment": "map",
          "icon-allow-overlap": true,
          // `icon-allow-overlap` ne dit QUE « dessine-moi quand même » : la boîte de la flèche
          // entre malgré tout dans la grille de collision et évince les symboles placés ensuite
          // — dont `stops-labels`, en dessous donc placée après (MapLibre place du haut vers le
          // bas). À 15 fps, les noms de stations clignotaient au passage des trains.
          "icon-ignore-placement": true,
          // Borne basse alignée sur MIN_VEHICLE_ZOOM : rien n'est visible en dessous (minzoom
          // ci-dessus), inutile d'interpoler depuis un zoom inatteignable pour cette couche.
          "icon-size": ["interpolate", ["linear"], ["zoom"], MIN_VEHICLE_ZOOM, 0.5, 13, 0.85, 16, 1.5],
        },
        paint: {
          // APPROXIMATE = course à un seul appel SIRI (36 % du flux mesuré) : le train est
          // borné à l'arrêt précédant son unique appel, souvent un terminus lointain. Atténué,
          // jamais masqué — un train perturbé doit rester visible.
          "icon-opacity": ["case", ["get", "approximate"], 0.45, 1],
        },
      });
      // Les couches viennent d'être créées : on y reporte une sélection / un surlignage qui
      // auraient été demandés avant que le style ne soit analysé (les setters sont alors
      // sans effet, faute de couche).
      this.applyHaloFilter();
      this.applyHighlightFilter();
      // La source vient d'être créée vide. Un render est nécessaire pour y verser les anims
      // déjà reçues (un poll peut avoir précédé le style ready).
      this.startLoop();
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

    // Suivi caméra : calculé et appliqué AVANT la sortie anticipée liée au zoom, et donc
    // indépendant du seuil — un dézoom sous MIN_VEHICLE_ZOOM ne doit pas mettre en pause le
    // recentrage sur le train suivi (sa flèche est masquée, mais la caméra continue de le
    // suivre ; sinon le train continue d'avancer pendant la pause et le retour au-dessus du
    // seuil produirait un saut de caméra brutal). Lookup direct par clé plutôt qu'un balayage
    // des ~705 anims (ce que faisait l'ancienne boucle de culling ci-dessous) : moins coûteux
    // qu'avant, à tous les niveaux de zoom.
    if (this.follow && this.selectedJourneyRef) {
      const followed = this.anims.get(this.selectedJourneyRef);
      if (followed) {
        this.map.jumpTo({ center: this.pointAt(followed, now) });
      }
    }

    // Sous MIN_VEHICLE_ZOOM, les trois couches sont masquées par leur `minzoom` : un `setData`
    // ici ne serait affiché par personne. On saute UNIQUEMENT ce qui suit (bounds, culling,
    // construction des features, setData) — pas tout le rendu : le suivi caméra ci-dessus vient
    // de s'exécuter, intact, quel que soit le zoom. Pas de nouvel écouteur pour rattraper le
    // retour au-dessus du seuil : MapLibre émet déjà "move" pendant un zoom (molette, pincement,
    // boutons de la NavigationControl, pas seulement un pan), et `moveHandler` (posé plus bas,
    // sur cet événement) rappelle déjà render() dès que le throttle le permet — donc dès que le
    // zoom repasse au-dessus, le prochain "move" (ou la prochaine frame de la boucle si des
    // trains sont encore en train d'animer) refait un `setData` à jour. Rien à faire côté
    // zoomend/moveend spécifiquement.
    if (this.map.getZoom() < MIN_VEHICLE_ZOOM) {
      return;
    }
    // Culling : on n'envoie que les véhicules du viewport élargi (marge 20 %). Les anims
    // restent maintenues pour tous → le tween survit à une sortie/entrée d'écran.
    const bounds = this.map.getBounds();
    const view = this.view;
    view.west = bounds.getWest();
    view.east = bounds.getEast();
    view.south = bounds.getSouth();
    view.north = bounds.getNorth();
    view.padX = (view.east - view.west) * 0.2;
    view.padY = (view.north - view.south) * 0.2;

    this.rendered.length = 0;
    for (const anim of this.anims.values()) {
      if (this.visibleLines && !this.visibleLines.has(anim.vehicle.lineId)) {
        continue;
      }
      const [lng, lat] = this.pointAt(anim, now);
      if (!this.inView(lng, lat)) {
        continue;
      }
      anim.feature.geometry.coordinates[0] = lng;
      anim.feature.geometry.coordinates[1] = lat;
      this.rendered.push(anim.feature);
    }
    source.setData({ type: "FeatureCollection", features: this.rendered });
    // Aucun travail d'état ici : les filtres des deux anneaux ne bougent qu'au changement de
    // sélection ou de surlignage (setSelected / setHighlighted), jamais par frame. Le suivi
    // caméra, lui, est géré tout en haut de render() — pas ici.
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
