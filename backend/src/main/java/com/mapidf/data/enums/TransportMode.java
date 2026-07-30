package com.mapidf.data.enums;

import java.util.Arrays;
import java.util.Optional;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Mode de transport ↔ {@code route_type} GTFS. Le périmètre suivi est piloté par
 * {@code app.network.modes} : passer du métro au tram est un changement de configuration.
 */
@Getter
@RequiredArgsConstructor
public enum TransportMode {
    TRAM(0),
    METRO(1),
    RAIL(2),
    BUS(3),
    CABLE(6),
    FUNICULAR(7);

    private final int gtfsRouteType;

    public static Optional<TransportMode> fromRouteType(int routeType) {
        return Arrays.stream(values())
            .filter(mode -> mode.gtfsRouteType == routeType)
            .findFirst();
    }
}
