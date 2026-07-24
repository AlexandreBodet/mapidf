package com.mapidf.controllers.lines;

import java.time.Instant;

import com.mapidf.position.Vehicle;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class VehicleResponse {

    String tripId;
    double lat;
    double lng;
    double bearing;
    String status;
    String headsign;
    String nextStop;
    Instant expectedTime;
    String source;

    public static VehicleResponse from(Vehicle vehicle) {
        return VehicleResponse.builder()
            .tripId(vehicle.tripId())
            .lat(vehicle.lat())
            .lng(vehicle.lng())
            .bearing(vehicle.bearing())
            .status(vehicle.status())
            .headsign(vehicle.headsign())
            .nextStop(vehicle.nextStop())
            .expectedTime(vehicle.expectedTime())
            .source(vehicle.source().name())
            .build();
    }
}
