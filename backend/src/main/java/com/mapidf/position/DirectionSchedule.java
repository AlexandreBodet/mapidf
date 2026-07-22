package com.mapidf.position;

import java.util.List;

public record DirectionSchedule(String terminusName, List<StopOnLine> stops) {
}
