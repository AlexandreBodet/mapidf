package com.mapidf.controllers.lines;

import java.util.List;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class ShapeResponse {

    String lineId;
    String color;
    double[][] shape;
    List<StopDto> stops;

    @Value
    @Builder
    public static class StopDto {
        String id;
        String name;
        double lat;
        double lng;
        List<String> platformIds;
    }
}
