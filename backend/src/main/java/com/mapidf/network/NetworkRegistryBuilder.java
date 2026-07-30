package com.mapidf.network;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;

import com.mapidf.data.entity.Branch;
import com.mapidf.data.entity.Route;
import com.mapidf.data.entity.Stop;
import com.mapidf.data.entity.StopTime;
import com.mapidf.data.repositories.BranchRepository;
import com.mapidf.data.repositories.StopRepository;
import com.mapidf.data.repositories.StopTimeRepository;
import com.mapidf.gtfs.LineDescriptor;
import com.mapidf.position.PositionEngine;
import com.mapidf.position.StopOnLine;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.locationtech.jts.linearref.LengthIndexedLine;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Construit un {@link NetworkSnapshot} depuis PostGIS. Appelé au démarrage (réhydratation, pour
 * ne pas retélécharger 109 Mo de GTFS) et après chaque refresh quotidien — jamais sur le chemin
 * d'une requête.
 *
 * <p>Trois requêtes, dont deux à {@code JOIN FETCH} explicite : en chargement paresseux, 37
 * branches × leurs stop_times × leurs arrêts feraient une centaine de requêtes. La troisième
 * ({@code findByGtfsIdIn} des stations parentes) n'a pas besoin de {@code JOIN FETCH} : {@link
 * Stop} ne porte aucune association, c'est un {@code IN} borné par construction (une station par
 * quai retenu, jamais par branche), pas une jointure à N+1.
 */
@Slf4j
@Service
@AllArgsConstructor
public class NetworkRegistryBuilder {

    private final BranchRepository branchRepository;
    private final StopTimeRepository stopTimeRepository;
    private final StopRepository stopRepository;

    @Transactional(readOnly = true)
    public NetworkSnapshot build() {
        List<Branch> branches = branchRepository.findAllWithRoute();
        List<StopTime> stopTimes = stopTimeRepository.findAllForRegistry();

        Map<UUID, List<StopTime>> byBranch = new LinkedHashMap<>();
        stopTimes.forEach(st -> byBranch
            .computeIfAbsent(st.getBranch().getId(), key -> new ArrayList<>()).add(st));

        Map<String, List<LineBranch>> branchesByRoute = new LinkedHashMap<>();
        Map<String, Route> routesByGtfsId = new LinkedHashMap<>();
        Map<String, TreeSet<String>> platformsByStation = new LinkedHashMap<>();
        Map<String, TreeSet<String>> lineIdsByStation = new LinkedHashMap<>();

        for (Branch branch : branches) {
            Route route = branch.getRoute();
            routesByGtfsId.putIfAbsent(route.getGtfsId(), route);
            // Chaque branche projette SES arrêts sur SA géométrie : c'est ce qui empêche un
            // arrêt de branche de se projeter n'importe où sur la branche voisine.
            LengthIndexedLine indexed = new LengthIndexedLine(branch.getGeom());
            List<StopTime> ordered = byBranch.getOrDefault(branch.getId(), List.of());

            List<StopOnLine> stops = ordered.stream()
                .map(st -> new StopOnLine(
                    PositionEngine.stopKey(st.getStop().getGtfsId()),
                    st.getStop().getName(),
                    indexed.project(st.getStop().getGeom().getCoordinate()),
                    st.getDepartureSec()))
                .toList();

            branchesByRoute.computeIfAbsent(route.getGtfsId(), key -> new ArrayList<>())
                .add(LineBranch.of(branch.getGtfsShapeId(), branch.getDirection(),
                    branch.getTerminusName(), branch.getGeom(), stops));

            String lineId = LineDescriptor.publicId(route.getShortName());
            for (StopTime st : ordered) {
                String stationId = stationKey(st.getStop());
                platformsByStation.computeIfAbsent(stationId, key -> new TreeSet<>())
                    .add(st.getStop().getGtfsId());
                lineIdsByStation.computeIfAbsent(stationId, key -> new TreeSet<>()).add(lineId);
            }
        }

        List<TrackedLine> lines = routesByGtfsId.values().stream()
            .map(route -> new TrackedLine(
                LineDescriptor.publicId(route.getShortName()), route.getGtfsId(),
                route.getSiriLineRef(), route.getShortName(), route.getColor(), route.getMode(),
                // Pas de getOrDefault : routesByGtfsId n'est peuplé que dans la boucle
                // ci-dessus, qui alimente branchesByRoute pour la même clé au même tour.
                branchesByRoute.get(route.getGtfsId())))
            .sorted(Comparator.comparing(TrackedLine::id))
            .toList();

        List<Station> stations = buildStations(platformsByStation, lineIdsByStation);

        log.info("[REGISTRY] {} ligne(s), {} branche(s), {} station(s)",
            lines.size(),
            lines.stream().mapToInt(line -> line.branches().size()).sum(),
            stations.size());
        return NetworkSnapshot.of(lines, stations);
    }

    private List<Station> buildStations(Map<String, TreeSet<String>> platformsByStation,
                                        Map<String, TreeSet<String>> lineIdsByStation) {
        // Les stations parentes sont persistées comme arrêts à part entière : elles portent leur
        // propre nom et leurs propres coordonnées. Mesuré : les 781 quais du métro ont tous un
        // parent présent en location_type=1, donc le repli sur le quai ne sert pas au métro.
        Map<String, Stop> byGtfsId = new LinkedHashMap<>();
        stopRepository.findByGtfsIdIn(platformsByStation.keySet())
            .forEach(stop -> byGtfsId.put(stop.getGtfsId(), stop));

        List<Station> stations = new ArrayList<>();
        platformsByStation.forEach((stationId, platforms) -> {
            Stop reference = byGtfsId.get(stationId);
            if (reference == null) {
                log.warn("[REGISTRY] station {} introuvable, ignorée", stationId);
                return;
            }
            stations.add(new Station(stationId, reference.getName(),
                reference.getGeom().getY(), reference.getGeom().getX(),
                List.copyOf(platforms),
                List.copyOf(lineIdsByStation.getOrDefault(stationId, new TreeSet<>()))));
        });
        return List.copyOf(stations);
    }

    /**
     * Rattache un quai à sa station parente. C'est ici, et nulle part en base, que se fait
     * désormais la résolution station → quais : {@code platformsByStation} est construit une fois
     * au build du registry et porté par {@link Station#platformIds()}, que {@code
     * StationDepartureService} lit en mémoire. Plus aucune requête ne filtre sur {@code
     * parent_station} — l'index {@code idx_stop_parent_station} créé en V3 n'a donc plus de
     * lecteur ; il est conservé parce qu'il ne coûte rien à ces volumes (781 quais métro) et
     * redeviendrait utile si on requêtait un jour par station parente. Le commentaire de V3, qui
     * le rattache encore à {@code StopRepository.findByParentStation}, décrit l'intention d'alors
     * et n'est pas réécrit : une migration déjà appliquée ne se modifie pas (cf. CLAUDE.md).
     */
    private static String stationKey(Stop stop) {
        String parent = stop.getParentStation();
        return (parent == null || parent.isBlank()) ? stop.getGtfsId() : parent;
    }
}
