package com.mapidf.gtfs;

import com.mapidf.MapIdfTest;
import com.mapidf.data.entity.Route;
import com.mapidf.data.repositories.RouteRepository;
import com.mapidf.data.repositories.StopRepository;
import com.mapidf.data.repositories.StopTimeRepository;
import com.mapidf.data.repositories.TripRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import static org.assertj.core.api.Assertions.assertThat;

@MapIdfTest
class GtfsStaticLoaderIT {

    @Autowired GtfsStaticLoader loader;
    @Autowired RouteRepository routeRepository;
    @Autowired StopRepository stopRepository;
    @Autowired StopTimeRepository stopTimeRepository;
    @Autowired TripRepository tripRepository;

    @Test
    void loadsLineIntoDb() throws Exception {
        try (var in = getClass().getResourceAsStream("/gtfs-mini.zip")) {
            loader.loadFromZip(in, "TEST9");
        }
        Route route = routeRepository.findByGtfsId("TEST9").orElseThrow();
        assertThat(route.getGeom().getNumPoints()).isEqualTo(3);
        assertThat(stopRepository.count()).isEqualTo(3);
        assertThat(stopTimeRepository.findScheduleByRouteGtfsId("TEST9")).hasSize(3);
    }

    @Test
    void loadsOnlyTheRequestedRouteFromAMultiRouteFeed() throws Exception {
        try (var in = getClass().getResourceAsStream("/gtfs-multi.zip")) {
            loader.loadFromZip(in, "TEST9");
        }

        Route route = routeRepository.findByGtfsId("TEST9").orElseThrow();
        assertThat(route.getGeom().getNumPoints()).isEqualTo(3);
        assertThat(stopRepository.count()).isEqualTo(3);
        assertThat(tripRepository.count()).isEqualTo(1);
        assertThat(stopTimeRepository.findScheduleByRouteGtfsId("TEST9")).hasSize(3);

        assertThat(routeRepository.findByGtfsId("TESTX")).isEmpty();
        assertThat(stopRepository.findAll())
            .extracting("gtfsId")
            .containsExactlyInAnyOrder("S1", "S2", "S3");
    }
}
