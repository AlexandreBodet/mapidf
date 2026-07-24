package com.mapidf.controllers.lines;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import com.mapidf.configurations.properties.LineProperties;
import com.mapidf.data.entity.Stop;
import com.mapidf.data.enums.ErrorCode;
import com.mapidf.data.repositories.StopRepository;
import com.mapidf.exceptions.ApiException;
import com.mapidf.gtfs.GtfsStaticService;
import com.mapidf.position.LineSchedule;
import com.mapidf.position.PositionEngine;
import com.mapidf.position.ScheduleProvider;
import com.mapidf.position.Vehicle;
import com.mapidf.rt.RealtimePoller;
import com.mapidf.services.NetworkQueryService;
import com.mapidf.services.StationDepartureService;
import lombok.AllArgsConstructor;
import org.locationtech.jts.geom.LineString;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/lines")
@AllArgsConstructor
public class LineController {

    private final NetworkQueryService networkQueryService;
    private final ScheduleProvider scheduleProvider;
    private final PositionEngine positionEngine;
    private final GtfsStaticService staticService;
    private final RealtimePoller poller;
    private final LineProperties lineProperties;
    private final StopRepository stopRepository;
    private final StationDepartureService departureService;

    @GetMapping("/{id}/shape")
    public ResponseEntity<ShapeResponse> shape(@PathVariable String id) {
        // MVP mono-ligne : on sert la ligne configurée (app.line.gtfs-route-id)
        ShapeResponse body = networkQueryService.getShape(lineProperties.gtfsRouteId());
        // Le tracé est statique (ne change qu'au rechargement du GTFS) : on autorise le
        // cache navigateur pour éviter de repayer la requête PostGIS à chaque onglet/refresh.
        return ResponseEntity.ok()
            .cacheControl(CacheControl.maxAge(Duration.ofMinutes(10)).cachePublic())
            .body(body);
    }

    @GetMapping("/{id}/vehicles")
    public VehiclesResponse vehicles(@PathVariable String id) {
        LineString line = staticService.getRouteGeometry();
        List<VehicleResponse> vehicles = List.of();
        if (line != null) {
            // MVP mono-ligne : on sert la ligne configurée (app.line.gtfs-route-id)
            LineSchedule schedule = scheduleProvider.getLineSchedule(line, lineProperties.gtfsRouteId());
            // Le snapshot couvre tout le réseau ; on n'en tire que la ligne demandée (par LineRef SIRI).
            List<Vehicle> computed = positionEngine.computeAll(
                line, schedule, poller.current().forLine(lineProperties.siriLineRef()), Instant.now());
            vehicles = computed.stream().map(VehicleResponse::from).toList();
        }
        return VehiclesResponse.builder()
            .asOf(Instant.now())
            .vehicles(vehicles)
            .build();
    }

    @GetMapping("/{id}/stations/{stationId}/departures")
    public DeparturesResponse departures(@PathVariable String id, @PathVariable String stationId) {
        // Résout la station → ses quais : soit par parent_station, soit un arrêt seul (gtfs_id).
        List<Stop> platforms = new ArrayList<>(stopRepository.findByParentStation(stationId));
        stopRepository.findByGtfsId(stationId).ifPresent(platforms::add);
        if (platforms.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, ErrorCode.STATION_NOT_FOUND);
        }
        Set<String> stopKeys = platforms.stream()
            .map(s -> PositionEngine.stopKey(s.getGtfsId()))
            .collect(Collectors.toSet());
        // MVP mono-ligne : on n'agrège que les courses de la ligne configurée.
        return departureService.departures(
            platforms.getFirst().getName(),
            stopKeys,
            poller.current().forLine(lineProperties.siriLineRef()),
            Instant.now(),
            3);
    }
}
