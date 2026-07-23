package com.mapidf.controllers.lines;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

import com.mapidf.configurations.properties.LineProperties;
import com.mapidf.gtfs.GtfsStaticService;
import com.mapidf.position.LineSchedule;
import com.mapidf.position.PositionEngine;
import com.mapidf.position.ScheduleProvider;
import com.mapidf.position.Vehicle;
import com.mapidf.rt.RealtimePoller;
import com.mapidf.services.NetworkQueryService;
import lombok.AllArgsConstructor;
import org.locationtech.jts.geom.LineString;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/lines")
@AllArgsConstructor
public class LineController {

    private static final ZoneId PARIS = ZoneId.of("Europe/Paris");

    private final NetworkQueryService networkQueryService;
    private final ScheduleProvider scheduleProvider;
    private final PositionEngine positionEngine;
    private final GtfsStaticService staticService;
    private final RealtimePoller poller;
    private final LineProperties lineProperties;

    @GetMapping("/{id}/shape")
    public ShapeResponse shape(@PathVariable String id) {
        // MVP mono-ligne : on sert la ligne configurée (app.line.gtfs-route-id)
        return networkQueryService.getShape(lineProperties.gtfsRouteId());
    }

    @GetMapping("/{id}/vehicles")
    public VehiclesResponse vehicles(@PathVariable String id) {
        LineString line = staticService.getRouteGeometry();
        List<VehicleResponse> vehicles = List.of();
        if (line != null) {
            Instant now = Instant.now();
            int nowSecOfDay = LocalTime.now(PARIS).toSecondOfDay();
            // MVP mono-ligne : on sert la ligne configurée (app.line.gtfs-route-id)
            LineSchedule schedule = scheduleProvider.getLineSchedule(line, lineProperties.gtfsRouteId());
            List<Vehicle> computed = positionEngine.computeAll(
                line, schedule, poller.current(), now, nowSecOfDay);
            vehicles = computed.stream().map(VehicleResponse::from).toList();
        }
        return VehiclesResponse.builder()
            .asOf(Instant.now())
            .vehicles(vehicles)
            .build();
    }
}
