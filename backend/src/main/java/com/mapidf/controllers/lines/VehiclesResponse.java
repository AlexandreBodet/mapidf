package com.mapidf.controllers.lines;

import java.time.Instant;
import java.util.List;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class VehiclesResponse {

    Instant asOf;
    List<VehicleResponse> vehicles;
}
