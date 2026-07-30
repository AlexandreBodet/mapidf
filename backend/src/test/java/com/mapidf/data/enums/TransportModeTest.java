package com.mapidf.data.enums;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class TransportModeTest {

    @Test
    void mapsGtfsRouteTypesMeasuredOnTheIdfmFeed() {
        // Valeurs relevées le 2026-07-29 sur routes.txt IDFM : 0=tram(17), 1=métro(16),
        // 2=rail(24), 3=bus(1410), 6=câble(1), 7=funiculaire(1).
        assertThat(TransportMode.fromRouteType(1)).contains(TransportMode.METRO);
        assertThat(TransportMode.fromRouteType(0)).contains(TransportMode.TRAM);
        assertThat(TransportMode.fromRouteType(2)).contains(TransportMode.RAIL);
        assertThat(TransportMode.fromRouteType(3)).contains(TransportMode.BUS);
        assertThat(TransportMode.fromRouteType(6)).contains(TransportMode.CABLE);
        assertThat(TransportMode.fromRouteType(7)).contains(TransportMode.FUNICULAR);
    }

    @Test
    void returnsEmptyForAnUnknownRouteType() {
        assertThat(TransportMode.fromRouteType(99)).isEqualTo(Optional.empty());
    }

    @Test
    void exposesTheGtfsRouteTypeOfEachMode() {
        assertThat(TransportMode.METRO.getGtfsRouteType()).isEqualTo(1);
    }
}
