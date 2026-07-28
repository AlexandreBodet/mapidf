package com.mapidf.services;

import com.mapidf.controllers.lines.ShapeResponse;
import com.mapidf.controllers.lines.ShapeResponse.StopDto;
import com.mapidf.data.entity.Route;
import com.mapidf.data.entity.Stop;
import com.mapidf.data.enums.ErrorCode;
import com.mapidf.data.repositories.RouteRepository;
import com.mapidf.data.repositories.StopRepository;
import com.mapidf.data.repositories.StopTimeRepository;
import com.mapidf.exceptions.ApiException;
import lombok.AllArgsConstructor;
import org.locationtech.jts.geom.Coordinate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@AllArgsConstructor
public class NetworkQueryService {

    private final RouteRepository routeRepository;
    private final StopTimeRepository stopTimeRepository;
    private final StopRepository stopRepository;

    @Transactional(readOnly = true)
    public ShapeResponse getShape(String gtfsRouteId) {
        Route route = routeRepository.findByGtfsId(gtfsRouteId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, ErrorCode.LINE_NOT_FOUND));

        double[][] shape = new double[route.getGeom().getNumPoints()][];
        Coordinate[] coordinates = route.getGeom().getCoordinates();
        for (int i = 0; i < coordinates.length; i++) {
            shape[i] = new double[]{coordinates[i].x, coordinates[i].y};
        }

        // Un quai par sens ⇒ deux arrêts GTFS par station physique. On les regroupe par
        // parent_station (clé canonique GTFS) ; à défaut on garde le quai seul (gtfs_id),
        // ce qui gère les arrêts à sens unique. lat/lng = centroïde des quais membres.
        Map<String, List<Stop>> byStation = stopRepository.findDistinctStopsByRouteGtfsId(gtfsRouteId).stream()
            .collect(Collectors.groupingBy(NetworkQueryService::stationKey, LinkedHashMap::new, Collectors.toList()));

        List<StopDto> stops = byStation.entrySet().stream()
            .map(e -> {
                List<Stop> platforms = e.getValue();
                double lat = platforms.stream().mapToDouble(s -> s.getGeom().getY()).average().orElse(0);
                double lng = platforms.stream().mapToDouble(s -> s.getGeom().getX()).average().orElse(0);
                return StopDto.builder()
                    .id(e.getKey())
                    .name(platforms.getFirst().getName())
                    .lat(lat)
                    .lng(lng)
                    .platformIds(platforms.stream().map(Stop::getGtfsId).toList())
                    .build();
            })
            .toList();

        return ShapeResponse.builder()
            .lineId(gtfsRouteId)
            .color(toCssColor(route.getColor()))
            .shape(shape)
            .stops(stops)
            .build();
    }

    private static String stationKey(Stop stop) {
        String parent = stop.getParentStation();
        return (parent == null || parent.isBlank()) ? stop.getGtfsId() : parent;
    }

    // route_color GTFS est un hex SANS '#' (ex. "D2D200") ; on renvoie une couleur CSS
    // valide (ex. "#D2D200") sinon MapLibre rejette la couche et le tracé n'apparaît pas.
    private static String toCssColor(String gtfsColor) {
        if (gtfsColor == null || gtfsColor.isBlank()) {
            return "#000000";
        }
        return gtfsColor.startsWith("#") ? gtfsColor : "#" + gtfsColor;
    }
}
