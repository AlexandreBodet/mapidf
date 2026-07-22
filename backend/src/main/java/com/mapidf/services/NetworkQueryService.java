package com.mapidf.services;

import java.util.List;

import com.mapidf.controllers.lines.ShapeResponse;
import com.mapidf.controllers.lines.ShapeResponse.StopDto;
import com.mapidf.data.entity.Route;
import com.mapidf.data.entity.StopTime;
import com.mapidf.data.enums.ErrorCode;
import com.mapidf.data.repositories.RouteRepository;
import com.mapidf.data.repositories.StopTimeRepository;
import com.mapidf.exceptions.ApiException;
import lombok.AllArgsConstructor;
import org.locationtech.jts.geom.Coordinate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@AllArgsConstructor
public class NetworkQueryService {

    private final RouteRepository routeRepository;
    private final StopTimeRepository stopTimeRepository;

    @Transactional(readOnly = true)
    public ShapeResponse getShape(String gtfsRouteId) {
        Route route = routeRepository.findByGtfsId(gtfsRouteId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, ErrorCode.LINE_NOT_FOUND));

        double[][] shape = new double[route.getGeom().getNumPoints()][];
        Coordinate[] coordinates = route.getGeom().getCoordinates();
        for (int i = 0; i < coordinates.length; i++) {
            shape[i] = new double[]{coordinates[i].x, coordinates[i].y};
        }

        List<StopDto> stops = stopTimeRepository.findScheduleByRouteGtfsId(gtfsRouteId).stream()
            .map(StopTime::getStop)
            .distinct()
            .map(s -> StopDto.builder()
                .id(s.getGtfsId())
                .name(s.getName())
                .lat(s.getGeom().getY())
                .lng(s.getGeom().getX())
                .build())
            .toList();

        return ShapeResponse.builder()
            .lineId(gtfsRouteId)
            .color(route.getColor())
            .shape(shape)
            .stops(stops)
            .build();
    }
}
