package com.mapidf.controllers.lines;

import java.time.Instant;
import java.util.List;

public record DeparturesResponse(String stationName, List<Direction> directions) {

    public record Direction(String destination, List<Passage> passages) {
    }

    public record Passage(Instant expectedTime, String status) {
    }
}
