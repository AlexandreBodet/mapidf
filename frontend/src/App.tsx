import { useEffect, useMemo, useRef, useState } from "react";
import maplibregl from "maplibre-gl";
import { useMap } from "./map/MapView";
import { useNetwork } from "./map/useNetwork";
import { useVehicles } from "./map/useVehicles";
import { useDisruptions } from "./api/useDisruptions";
import { VehiclePanel } from "./ui/VehiclePanel";
import { StopPanel } from "./ui/StopPanel";
import { LinePicker } from "./ui/LinePicker";
import { NetworkStatus } from "./ui/NetworkStatus";
import { NetworkSummary } from "./ui/NetworkSummary";
import { SheetFooter } from "./ui/SheetFooter";
import { FloatingCard } from "./ui/FloatingCard";
import { PanelHeader } from "./ui/PanelHeader";
import { Sheet } from "./ui/Sheet";
import { useIsNarrow, useViewportHeight } from "./ui/useViewport";
import { humanOrder } from "./ui/lineOrder";
import { clampPadding, mapPadding, PEEK_HEIGHT, type Cran } from "./ui/sheetCrans";
import { fetchDepartures } from "./api/network";
import { VEHICLE_POLL_MS } from "./api/config";
import type { DeparturesResponse, Vehicle } from "./api/types";
import type { VehicleFeatureProperties } from "./map/VehicleLayer";

// Un clic direct sur une flèche ne fournit que journeyRef (VehicleFeatureProperties, allégée
// tâche 14 : headsign/nextStop/expectedTime/status n'y sont plus). Le Vehicle complet vient
// de la Map tenue par useVehicles, qui rappelle onSelected dès que selectedJourneyRef change.
type Selected = Vehicle | null;

export default function App() {
  const container = useRef<HTMLDivElement>(null);
  const map = useMap(container);
  const departuresAbort = useRef<AbortController | null>(null);
  const [selected, setSelected] = useState<Selected>(null);
  const [selectedJourneyRef, setSelectedJourneyRef] = useState<string | null>(null);
  const [follow, setFollow] = useState(false);
  const [station, setStation] = useState<DeparturesResponse | null>(null);
  const [selectedStationId, setSelectedStationId] = useState<string | null>(null);
  const [visibleLines, setVisibleLines] = useState<Set<string> | null>(null);
  const [counts, setCounts] = useState<Map<string, number>>(new Map());
  // Horodatage du dernier snapshot servi : affiché sous le compteur de trains, il informe de la
  // date de mise à jour de la donnée (Licence Mobilité, art. 5.7 « neutralité et loyauté »).
  const [asOf, setAsOf] = useState<string | null>(null);
  const [stale, setStale] = useState(false);
  // Remonté ici parce que la carte en dépend : panneau ouvert = stations perturbées mises en
  // évidence par un halo.
  const [disruptionsOpen, setDisruptionsOpen] = useState(false);
  // Chevron de repli du sélecteur (branche large uniquement) : déplié par défaut pour ne rien
  // changer à ce qui est vu au premier chargement.
  const [pickerExpanded, setPickerExpanded] = useState(true);
  const isNarrow = useIsNarrow();
  const viewportHeight = useViewportHeight();
  // Détenu ici, pas dans la feuille : la carte en dérive son padding de caméra (tâche 4), et
  // ouvrir une station doit pouvoir remonter la feuille.
  const [cran, setCran] = useState<Cran>("apercu");
  // Hauteur réelle de l'aperçu, mesurée par la feuille : elle se dimensionne sur son contenu, dont
  // la hauteur dépend du nombre de lignes perturbées et de l'alerte de gel.
  const [peekHeight, setPeekHeight] = useState(PEEK_HEIGHT);
  // Trains concernés par les passages de la station ouverte (surlignés sur la carte).
  // Une correspondance groupe plusieurs lignes (task 12) : on aplatit lignes puis directions.
  const highlightedJourneyRefs = useMemo(
    () =>
      new Set(
        station?.lines.flatMap((l) => l.directions).flatMap((d) => d.passages.map((p) => p.journeyRef)) ?? [],
      ),
    [station],
  );
  const disruptions = useDisruptions();
  const { network, status } = useNetwork(map, visibleLines, disruptions.stationSeverity, disruptionsOpen);
  // À chaque poll, rafraîchit le panneau avec la donnée fraîche du train suivi
  // (prochain arrêt + ETA vivants). Si le train quitte le flux, on garde le dernier état connu.
  useVehicles(map, network, selectedJourneyRef, follow, (v) => {
    if (v) {
      setSelected(v);
    }
  }, (next, at, failing) => {
    // null sur échec : on garde le dernier décompte et le dernier horodatage affichés, en
    // signalant qu'ils ne bougent plus.
    if (next) {
      setCounts(next);
    }
    if (at) {
      setAsOf(at);
    }
    setStale(failing);
  }, highlightedJourneyRefs, visibleLines);

  const toggleLine = (lineId: string) => {
    setVisibleLines((current) => {
      // Premier clic depuis « toutes » (visibleLines === null) : on isole la ligne cliquée
      // plutôt que de la retirer d'un ensemble complet — c'est l'intention la plus fréquente
      // sur 16 lignes (voir une seule ligne), et retirer demanderait 15 clics sinon.
      if (current === null) {
        return new Set([lineId]);
      }
      const all = new Set(network?.lines.map((line) => line.id) ?? []);
      const next = new Set(current);
      if (next.has(lineId)) {
        // Ne pas vider la carte d'un clic : si c'est la dernière ligne encore visible, on la
        // garde (no-op). « tout afficher » reste l'échappatoire explicite, mais un clic isolé
        // ne doit pas produire un état vide silencieux.
        if (next.size === 1) {
          // `current`, pas `next` : renvoyer un Set neuf pour un no-op déclenche un re-render et
          // le refiltrage des 321 stations pour rien.
          return current;
        }
        next.delete(lineId);
      } else {
        next.add(lineId);
      }
      return next.size === all.size ? null : next;
    });
  };

  // Les écouteurs de clic sont posés une fois pour toutes (deps `[map]`) : ils ne peuvent pas
  // lire ces trois valeurs directement, elles y seraient figées à leur valeur au montage.
  const sheet = useRef({ isNarrow, viewportHeight, cran });
  sheet.current = { isNarrow, viewportHeight, cran };

  // Le padding est posé AVANT le recentrage de l'appelant : sinon `easeTo` animerait vers un
  // centre calculé avec l'ancien padding, puis `setPadding` déplacerait la vue d'un coup sec.
  const openSheet = (map: maplibregl.Map) => {
    const { isNarrow, viewportHeight, cran } = sheet.current;
    // Hors mode étroit il n'y a pas de feuille : monter le cran ferait retourner l'effet de
    // padding, dont le `setPadding` tuerait l'`easeTo` que l'appelant lance juste après.
    if (!isNarrow) {
      return;
    }
    const target = cran === "apercu" ? "moitie" : cran;
    sheet.current.cran = target;
    setCran(target);
    map.setPadding({ top: 0, right: 0, bottom: mapPadding(target, viewportHeight), left: 0 });
  };

  useEffect(() => {
    if (!map) {
      return;
    }
    const onClick = (e: maplibregl.MapLayerMouseEvent) => {
      const props = e.features?.[0]?.properties as VehicleFeatureProperties | undefined;
      if (!props) {
        return;
      }
      setStation(null);
      setSelectedStationId(null);
      map.setFilter("stops-selected", ["==", ["get", "id"], "__none__"]);
      // Pas de setSelected ici : la feature ne porte plus le Vehicle complet (tâche 14).
      // useVehicles rappelle onSelected avec le Vehicle depuis sa Map dès que
      // selectedJourneyRef change, ci-dessous.
      setSelectedJourneyRef(props.journeyRef);
      setFollow(true);
      openSheet(map);
    };
    map.on("click", "vehicles", onClick);
    const onStationClick = async (e: maplibregl.MapLayerMouseEvent) => {
      const id = e.features?.[0]?.properties?.id as string | undefined;
      const coords = (e.features?.[0]?.geometry as GeoJSON.Point | undefined)?.coordinates;
      if (!id) {
        return;
      }
      // Sélection exclusive : ouvrir une station ferme le suivi d'un train.
      setSelected(null);
      setSelectedJourneyRef(null);
      setFollow(false);
      map.setFilter("stops-selected", ["==", ["get", "id"], id]);
      // Avant l'easeTo : il doit s'animer vers le centre définitif, padding compris.
      openSheet(map);
      if (coords) {
        map.easeTo({ center: coords as [number, number] });
      }
      setSelectedStationId(id);
      departuresAbort.current?.abort();
      const controller = new AbortController();
      departuresAbort.current = controller;
      try {
        const fresh = await fetchDepartures(id, controller.signal);
        if (!controller.signal.aborted) {
          setStation(fresh);
        }
      } catch {
        if (!controller.signal.aborted) {
          setStation(null);
        }
      }
    };
    map.on("click", "stops", onStationClick);
    map.on("click", "stops-labels", onStationClick);
    const enter = () => { map.getCanvas().style.cursor = "pointer"; };
    const leave = () => { map.getCanvas().style.cursor = ""; };
    map.on("mouseenter", "vehicles", enter);
    map.on("mouseleave", "vehicles", leave);
    return () => {
      map.off("click", "vehicles", onClick);
      map.off("click", "stops", onStationClick);
      map.off("click", "stops-labels", onStationClick);
      map.off("mouseenter", "vehicles", enter);
      map.off("mouseleave", "vehicles", leave);
    };
  }, [map]);

  useEffect(() => {
    if (!map) {
      return;
    }
    // Le suivi appelle jumpTo à chaque frame : la carte est en mouvement permanent,
    // donc `movestart` ne se redéclenche pas sur un geste utilisateur. On écoute plutôt
    // les événements d'entrée bruts, qui arrivent quel que soit l'état d'animation.
    const stopFollow = () => setFollow(false);
    map.on("mousedown", stopFollow);
    map.on("wheel", stopFollow);
    map.on("touchstart", stopFollow);
    return () => {
      map.off("mousedown", stopFollow);
      map.off("wheel", stopFollow);
      map.off("touchstart", stopFollow);
    };
  }, [map]);

  // La feuille flotte au-dessus d'une carte qui garde tout le viewport : rien ne rétrécit, donc
  // aucun map.resize(). Un seul padding suffit à ce que TOUS les recentrages de MapLibre — le
  // easeTo du clic station comme le jumpTo par frame du suivi — se posent au-dessus d'elle.
  useEffect(() => {
    if (!map) {
      return;
    }
    // L'aperçu se dimensionne sur son contenu : sa hauteur est mesurée, pas calculée.
    const sheetHeight = cran === "apercu"
      ? clampPadding(peekHeight, viewportHeight)
      : mapPadding(cran, viewportHeight);
    const bottom = isNarrow ? sheetHeight : 0;
    // setPadding délègue à jumpTo, qui appelle stop() AVANT de comparer : réécrire une valeur
    // inchangée tuerait l'easeTo qu'openSheet vient de lancer côté appelant.
    if (map.getPadding().bottom !== bottom) {
      map.setPadding({ top: 0, right: 0, bottom, left: 0 });
    }
  }, [map, isNarrow, cran, viewportHeight, peekHeight]);

  // Le panneau passages est rafraîchi au rythme du poll tant qu'une station est sélectionnée,
  // pour que les ETA vivent et que les passages partis disparaissent (sinon on affiche des
  // « imminent » fantômes figés au fetch initial).
  useEffect(() => {
    if (!selectedStationId) {
      return;
    }
    let cancelled = false;
    let timer: number;
    const controller = new AbortController();
    const tick = async () => {
      try {
        const fresh = await fetchDepartures(selectedStationId, controller.signal);
        if (!cancelled) {
          setStation(fresh);
        }
      } catch {
        // on conserve l'affichage courant
      }
      if (!cancelled) {
        timer = window.setTimeout(tick, VEHICLE_POLL_MS);
      }
    };
    timer = window.setTimeout(tick, VEHICLE_POLL_MS);
    return () => {
      cancelled = true;
      window.clearTimeout(timer);
      controller.abort();
    };
  }, [selectedStationId]);

  const clearSelection = () => {
    setSelected(null);
    setSelectedJourneyRef(null);
    setFollow(false);
  };

  const closeStation = () => {
    departuresAbort.current?.abort();
    departuresAbort.current = null;
    setStation(null);
    setSelectedStationId(null);
    map?.setFilter("stops-selected", ["==", ["get", "id"], "__none__"]);
  };

  const followTrainFromPanel = (journeyRef: string) => {
    closeStation();
    setSelected(null);
    setSelectedJourneyRef(journeyRef);
    setFollow(true);
  };

  // Trié une fois ici : `App` a besoin de l'ordre pour dériver les lignes perturbées, et le
  // sélecteur pour ses pastilles. Deux tris divergeraient.
  const orderedLines = useMemo(
    () => [...(network?.lines ?? [])].sort(humanOrder),
    [network],
  );
  const disrupted = useMemo(
    () => orderedLines.filter((line) => disruptions.byLine.has(line.id)),
    [orderedLines, disruptions.byLine],
  );
  const total = [...counts.values()].reduce((sum, n) => sum + n, 0);

  const networkSummary = (
    <NetworkSummary
      total={total}
      disruptedCount={disrupted.length}
      disruptionsOpen={disruptionsOpen}
      onToggleDisruptions={() => setDisruptionsOpen((open) => !open)}
      canShowAll={visibleLines !== null}
      onShowAll={() => setVisibleLines(null)}
      // La feuille étroite a déjà ses crans pour replier le sélecteur : le chevron n'a de sens
      // que sur la carte flottante du rendu large.
      collapsible={!isNarrow}
      expanded={pickerExpanded}
      onToggleExpanded={() => setPickerExpanded((open) => !open)}
    />
  );
  const linePicker = (
    <LinePicker
      lines={orderedLines}
      disrupted={disrupted}
      counts={counts}
      disruptions={disruptions.byLine}
      disruptionsOpen={disruptionsOpen}
      visible={visibleLines}
      onToggle={toggleLine}
    />
  );
  // Les deux fiches n'ont jamais eu la même largeur : 280 px pour une station, 260 pour un
  // train. Les unifier serait une régression visible sur desktop.
  const ficheWidth = station ? 280 : 260;
  // Une seule fiche existe à la fois : `App` vide la sélection train à l'ouverture d'une station
  // et l'inverse. C'est ce qui permet à la feuille de la tâche 3 de n'avoir qu'un contenu.
  const ficheHeader = station
    ? <PanelHeader title={station.stationName} onClose={closeStation} />
    : selected
      ? <PanelHeader title={`→ ${selected.headsign}`} onClose={clearSelection} />
      : null;
  const ficheBody = station
    ? (
      <StopPanel
        data={station}
        onSelectTrain={followTrainFromPanel}
        // Isolement inconditionnel : même intention qu'un clic dans LinePicker, quel que
        // soit visibleLines courant. La station reste affichée par construction : elle est
        // desservie par lineId (c'est sa propre pastille), donc son filtre dans useNetwork
        // (station.lineIds.some(id => visibleLines.has(id))) la garde visible.
        onSelectLine={(lineId) => setVisibleLines(new Set([lineId]))}
      />
    )
    : selected
      ? (
        <VehiclePanel
          vehicle={selected}
          following={follow}
          onFollow={() => setFollow((f) => !f)}
        />
      )
      : null;

  return (
    <>
      <div ref={container} style={{ position: "absolute", inset: 0 }} />
      <NetworkStatus status={status} />
      {isNarrow ? (
        <Sheet
          cran={cran}
          onCranChange={setCran}
          viewportHeight={viewportHeight}
          summary={ficheHeader ?? networkSummary}
          footer={<SheetFooter stale={stale} asOf={asOf} />}
          onPeekHeight={setPeekHeight}
          label={station || selected ? "Détail" : "État du réseau"}
          asOf={asOf}
        >
          {ficheBody ?? linePicker}
        </Sheet>
      ) : (
        <>
          {ficheHeader && (
            <FloatingCard anchor="top-right" style={{ width: ficheWidth, maxHeight: "70dvh", overflowY: "auto" }}>
              {ficheHeader}
              {ficheBody}
            </FloatingCard>
          )}
          <FloatingCard
            anchor="bottom-left"
            style={{ padding: "10px 12px", font: "13px sans-serif", maxWidth: 300 }}
          >
            {networkSummary}
            {pickerExpanded && linePicker}
            <SheetFooter stale={stale} asOf={asOf} />
          </FloatingCard>
        </>
      )}
    </>
  );
}
