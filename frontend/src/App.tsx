import { useEffect, useMemo, useRef, useState } from "react";
import type { Map as MlMap, MapLayerMouseEvent } from "maplibre-gl";
import type { Point } from "geojson";
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
import { StaleWarning } from "./ui/StaleWarning";
import { FloatingCard } from "./ui/FloatingCard";
import styles from "./App.module.css";
import { PanelHeader } from "./ui/PanelHeader";
import { Sheet } from "./ui/Sheet";
import { useIsNarrow, useViewportHeight } from "./ui/useViewport";
import { humanOrder } from "./ui/lineOrder";
import { clampPadding, mapPadding, PEEK_HEIGHT, type Cran } from "./ui/sheetCrans";
import { toggleLine as toggleLineSubset } from "./ui/toggleLine";
import { fetchDepartures } from "./api/network";
import { VEHICLE_POLL_MS } from "./api/config";
import type { DeparturesResponse, Vehicle } from "./api/types";
import type { VehicleFeatureProperties } from "./map/VehicleLayer";
import { decodePermalink, encodePermalink } from "./api/permalink";
import { revealMore, revealedCountFor } from "./ui/passageReveal";
import { whenStyleReady } from "./map/mapReady";

// Un clic direct sur une flèche ne fournit que journeyRef (VehicleFeatureProperties, allégée
// tâche 14 : headsign/nextStop/expectedTime/status n'y sont plus). Le Vehicle complet vient
// de la Map tenue par useVehicles, qui rappelle onSelected dès que selectedJourneyRef change.
type Selected = Vehicle | null;

export default function App() {
  const container = useRef<HTMLDivElement>(null);
  const map = useMap(container);
  const departuresAbort = useRef<AbortController | null>(null);
  // Lu une seule fois, à la création du composant : les changements ultérieurs de l'URL
  // (navigation externe) ne sont pas suivis, seul `replaceState` (plus bas) l'écrit depuis
  // l'état de l'appli — jamais l'inverse après ce point.
  const [initialPermalink] = useState(() => decodePermalink(window.location.search));
  const [selected, setSelected] = useState<Selected>(null);
  const [selectedJourneyRef, setSelectedJourneyRef] = useState<string | null>(initialPermalink.journeyRef);
  // Même comportement qu'un clic direct sur un train sur la carte (onClick de la couche
  // `vehicles`, plus bas dans ce fichier, qui pose déjà `setFollow(true)`).
  const [follow, setFollow] = useState(initialPermalink.journeyRef !== null);
  const [station, setStation] = useState<DeparturesResponse | null>(null);
  const [selectedStationId, setSelectedStationId] = useState<string | null>(null);
  // Combien de passages sont dépliés par direction dans StopPanel (UX-5e) — tenu ici, pas dans
  // StopPanel, pour que highlightedJourneyRefs (plus bas) sache exactement quels trains sont
  // effectivement listés, y compris après un clic sur "Voir plus".
  const [revealedPassages, setRevealedPassages] = useState<Record<string, number>>({});
  // Remise à zéro sur changement de station, en phase de rendu plutôt que dans un effet (pattern
  // React « ajuster un état quand une prop change », cf. react.dev/learn/you-might-not-need-an-effect) :
  // pas de rendu intermédiaire avec l'ancien dépliage affiché sur la nouvelle station.
  const [revealedForStation, setRevealedForStation] = useState(selectedStationId);
  if (revealedForStation !== selectedStationId) {
    setRevealedForStation(selectedStationId);
    setRevealedPassages({});
  }
  const [visibleLines, setVisibleLines] = useState<Set<string> | null>(
    initialPermalink.visibleLineIds ? new Set(initialPermalink.visibleLineIds) : null,
  );
  const [counts, setCounts] = useState<Map<string, number>>(new Map());
  // Débloque l'effet d'écriture (plus bas) : vrai tout de suite s'il n'y avait pas de station à
  // restaurer, sinon posé par l'effet de restauration une fois qu'il a fini (id trouvé ou non).
  const [urlRestored, setUrlRestored] = useState(initialPermalink.stationId === null);
  const stationRestored = useRef(false);
  // Horodatage du dernier snapshot servi : affiché sous le compteur de trains, il informe de la
  // date de mise à jour de la donnée (Licence Mobilité, art. 5.7 « neutralité et loyauté »).
  const [asOf, setAsOf] = useState<string | null>(null);
  const [stale, setStale] = useState(false);
  const [inService, setInService] = useState(true);
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
        station?.lines.flatMap((l) =>
          l.directions.flatMap((d) =>
            d.passages
              .slice(0, revealedCountFor(revealedPassages, l.lineId, d.destination))
              .map((p) => p.journeyRef),
          ),
        ) ?? [],
      ),
    [station, revealedPassages],
  );
  const disruptions = useDisruptions();
  const { network, status } = useNetwork(map, visibleLines, disruptions.stationSeverity, disruptionsOpen);
  // À chaque poll, rafraîchit le panneau avec la donnée fraîche du train suivi
  // (prochain arrêt + ETA vivants). Si le train quitte le flux, on garde le dernier état connu.
  useVehicles(map, network, selectedJourneyRef, follow, (v) => {
    if (v) {
      setSelected(v);
    }
  }, (outcome) => {
    // null sur échec : on garde le dernier décompte, le dernier horodatage et le dernier état de
    // service affichés, en signalant qu'ils ne bougent plus.
    if (outcome.counts) {
      setCounts(outcome.counts);
    }
    if (outcome.asOf) {
      setAsOf(outcome.asOf);
    }
    if (outcome.inService !== null) {
      setInService(outcome.inService);
    }
    setStale(outcome.failing);
  }, highlightedJourneyRefs, visibleLines);

  const toggleLine = (lineId: string) => {
    setVisibleLines((current) => toggleLineSubset(current, lineId, network?.lines.length ?? 0));
  };

  // Les écouteurs de clic sont posés une fois pour toutes (deps `[map]`) : ils ne peuvent pas
  // lire ces trois valeurs directement, elles y seraient figées à leur valeur au montage.
  const sheet = useRef({ isNarrow, viewportHeight, cran });
  sheet.current = { isNarrow, viewportHeight, cran };

  // Le padding est posé AVANT le recentrage de l'appelant : sinon `easeTo` animerait vers un
  // centre calculé avec l'ancien padding, puis `setPadding` déplacerait la vue d'un coup sec.
  const openSheet = (map: MlMap) => {
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

  // Cœur de handleStationClick, extrait pour être rejoué depuis la recherche (UX-5a) exactement
  // comme depuis un clic carte : mêmes filtres, même vol de caméra, même fetch des passages.
  const selectStation = async (map: MlMap, id: string, coords: [number, number] | undefined) => {
    // Sélection exclusive : ouvrir une station ferme le suivi d'un train.
    setSelected(null);
    setSelectedJourneyRef(null);
    setFollow(false);
    map.setFilter("stops-selected", ["==", ["get", "id"], id]);
    // Avant l'easeTo : il doit s'animer vers le centre définitif, padding compris.
    openSheet(map);
    if (coords) {
      map.easeTo({ center: coords });
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

  // Restauration d'un lien partagé (UX-5b) : la station a besoin de `map` (setFilter, easeTo) et
  // de `network` (retrouver ses coordonnées pour recentrer la caméra, comme un clic direct). Le
  // ref empêche de rejouer la restauration si `map`/`network` changent à nouveau plus tard.
  useEffect(() => {
    if (stationRestored.current || !map || !network) {
      return;
    }
    const target = initialPermalink.stationId
      ? network.stations.find((s) => s.id === initialPermalink.stationId)
      : undefined;
    if (target) {
      // `selectStation` est async : un throw synchrone dans son premier `map.setFilter` (style pas
      // encore analysé) devient une promesse rejetée, invisible à un try/catch ici — on attend donc
      // que la couche que ce filtre cible existe déjà (posée par useNetwork via le même
      // whenStyleReady), plutôt que d'essayer d'attraper un rejet qu'on ne peut pas voir.
      return whenStyleReady(map, () => {
        if (!map.getLayer("stops-selected")) {
          throw new Error("Style is not done loading.");
        }
        stationRestored.current = true;
        void selectStation(map, target.id, [target.lng, target.lat]);
        // Débloque l'écriture seulement une fois la restauration réellement lancée : sinon l'URL se
        // viderait de `?station=...` avant que la restauration n'ait eu la moindre chance d'aboutir.
        setUrlRestored(true);
      });
    }
    // Rien à restaurer : aucune opération carte, aucune raison d'attendre le style. Appel direct
    // volontaire : la sortir de ce `if` la rendrait inconditionnelle et romprait la branche
    // `target`, qui doit au contraire ATTENDRE la restauration réelle avant de débloquer l'écriture.
    stationRestored.current = true;
    // eslint-disable-next-line react-hooks/set-state-in-effect -- cf. commentaire ci-dessus
    setUrlRestored(true);
    // eslint-disable-next-line react-hooks/exhaustive-deps -- initialPermalink.stationId est figé au montage et selectStation est stable (cf. plus haut) ; les lister re-déclencherait cet effet sans jamais changer son résultat
  }, [map, network]);

  // Un clic direct sur un train ouvre la feuille (openSheet) ; la restauration par URL doit faire
  // pareil pour ne pas laisser un lien de train partagé se rouvrir replié sous 720 px.
  const trainRestored = useRef(false);
  useEffect(() => {
    if (trainRestored.current || !map || !initialPermalink.journeyRef) {
      return;
    }
    trainRestored.current = true;
    openSheet(map);
    // eslint-disable-next-line react-hooks/exhaustive-deps -- initialPermalink.journeyRef est figé au montage ; le lister re-déclencherait cet effet sans jamais changer son résultat (le ref bloque déjà toute deuxième exécution)
  }, [map]);

  // Écrit l'état de sélection dans l'URL à chaque changement, pour qu'elle reste copiable à tout
  // moment (UX-5b). `replaceState` seul, jamais `pushState` : le bouton Précédent doit continuer
  // à quitter l'appli, pas naviguer entre sélections (spec § 2).
  useEffect(() => {
    if (!urlRestored) {
      return;
    }
    const query = encodePermalink({
      stationId: selectedStationId,
      journeyRef: selectedJourneyRef,
      visibleLineIds: visibleLines ? [...visibleLines] : null,
    });
    window.history.replaceState(null, "", `${window.location.pathname}${query}${window.location.hash}`);
  }, [urlRestored, selectedStationId, selectedJourneyRef, visibleLines]);

  useEffect(() => {
    if (!map) {
      return;
    }
    const onClick = (e: MapLayerMouseEvent) => {
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
    const handleStationClick = (e: MapLayerMouseEvent) => {
      const id = e.features?.[0]?.properties?.id as string | undefined;
      const coords = (e.features?.[0]?.geometry as Point | undefined)?.coordinates as
        [number, number] | undefined;
      if (!id) {
        return;
      }
      void selectStation(map, id, coords);
    };
    // MapLibre ignore la promesse rendue par un handler `async`. On l'écarte donc ici, dans un
    // enrobage **synchrone et unique** : `on` et `off` doivent recevoir la MÊME référence, sinon
    // le nettoyage de l'effet ne retire rien et les écouteurs s'empilent à chaque montage.
    const onStationClick = (e: MapLayerMouseEvent) => {
      void handleStationClick(e);
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
    // eslint-disable-next-line react-hooks/exhaustive-deps -- selectStation est stable en pratique (ne ferme que sur des refs et des setters) ; l'ajouter re-poserait les écouteurs à chaque render sans changer leur comportement
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

  // Refermer une fiche détruit l'élément focalisé : sans retour explicite, le focus retombe sur
  // `body` et la tabulation repart du début du document. Le canevas est le seul point de retour
  // honnête — la fiche s'ouvre par un clic carte, il n'y a pas d'élément déclencheur à qui rendre le
  // focus — et il est focusable par construction (MapLibre y pose `tabindex="0"`).
  const focusMap = () => map?.getCanvas().focus();

  // Pont entre la recherche (UX-5a) et la sélection existante. Sur mobile, le champ de recherche
  // est démonté dès qu'une station est sélectionnée (Sheet à contenu unique, App.tsx:407) : sans
  // retour explicite le focus retomberait sur `body`, même défaut que closeStation corrige à la
  // fermeture. En desktop LinePicker et sa recherche survivent à la sélection (FloatingCard
  // séparée) : le focus reste sur le champ, pour pouvoir enchaîner une deuxième recherche.
  const selectStationFromSearch = (id: string, coords: [number, number]) => {
    if (!map) {
      return;
    }
    void selectStation(map, id, coords);
    if (isNarrow) {
      focusMap();
    }
  };

  /** Vide la station sans toucher au focus : `followTrainFromPanel` enchaîne sur une autre fiche. */
  const resetStation = () => {
    departuresAbort.current?.abort();
    departuresAbort.current = null;
    setStation(null);
    setSelectedStationId(null);
    map?.setFilter("stops-selected", ["==", ["get", "id"], "__none__"]);
  };

  const closeStation = () => {
    resetStation();
    focusMap();
  };

  const clearSelection = () => {
    setSelected(null);
    setSelectedJourneyRef(null);
    setFollow(false);
    focusMap();
  };

  const followTrainFromPanel = (journeyRef: string) => {
    resetStation();
    setSelected(null);
    setSelectedJourneyRef(journeyRef);
    setFollow(true);
  };

  // Écouteur sur `document`, et non un `onKeyDown` sur la fiche : au moment de fermer, le focus est
  // le plus souvent sur le canevas (la fiche s'ouvre par un clic carte), donc un gestionnaire React
  // posé sur le panneau ne verrait jamais la touche. Le ref suit le motif de `sheet` ci-dessus :
  // l'écouteur est posé une fois et ne peut pas lire ces valeurs sans les figer au montage.
  const onEscape = useRef(() => {});
  onEscape.current = () => {
    if (station || selectedStationId) {
      closeStation();
    } else if (selected || selectedJourneyRef) {
      clearSelection();
    }
  };

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        onEscape.current();
      }
    };
    document.addEventListener("keydown", onKeyDown);
    return () => document.removeEventListener("keydown", onKeyDown);
  }, []);

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
      inService={inService}
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
      onSelectStation={selectStationFromSearch}
    />
  );
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
        revealed={revealedPassages}
        onReveal={(lineId, destination) =>
          setRevealedPassages((prev) => revealMore(prev, lineId, destination))
        }
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
    <main>
      {/* Landmark du contenu principal : sans lui, une navigation par landmarks ne trouve que les
          deux régions nommées et jamais le contenu. Statique, donc sans effet sur le positionnement
          absolu de la carte et des panneaux. */}
      {/* Un plan de document commence par un titre de niveau 1. Masqué visuellement : le titre
          existe déjà dans `<title>` et l'écran est tout entier occupé par la carte. */}
      <h1 className={styles.srOnly}>MapIDF — métro d'Île-de-France</h1>
      <div ref={container} className={styles.map} />
      <NetworkStatus status={status} />
      {isNarrow ? (
        <Sheet
          cran={cran}
          onCranChange={setCran}
          viewportHeight={viewportHeight}
          header={ficheHeader}
          summary={ficheHeader ? null : networkSummary}
          alert={<StaleWarning stale={stale} />}
          footer={<SheetFooter asOf={asOf} />}
          onPeekHeight={setPeekHeight}
          label={station || selected ? "Détail" : "État du réseau"}
          asOf={asOf}
        >
          {ficheBody ?? linePicker}
        </Sheet>
      ) : (
        <>
          {ficheHeader && (
            <FloatingCard
              anchor="top-right"
              label="Détail"
              className={station ? styles.ficheStation : styles.ficheTrain}
            >
              {ficheHeader}
              {ficheBody}
            </FloatingCard>
          )}
          <FloatingCard anchor="bottom-left" label="État du réseau" className={styles.reseau}>
            {networkSummary}
            {pickerExpanded && linePicker}
            <StaleWarning stale={stale} />
            <SheetFooter asOf={asOf} />
          </FloatingCard>
        </>
      )}
    </main>
  );
}
