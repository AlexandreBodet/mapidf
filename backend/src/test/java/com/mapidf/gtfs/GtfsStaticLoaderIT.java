package com.mapidf.gtfs;

import com.mapidf.MapIdfTest;
import com.mapidf.data.entity.Branch;
import com.mapidf.data.entity.Route;
import com.mapidf.data.repositories.BranchRepository;
import com.mapidf.data.repositories.RouteRepository;
import com.mapidf.data.repositories.StopRepository;
import com.mapidf.data.repositories.StopTimeRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import static org.assertj.core.api.Assertions.assertThat;

@MapIdfTest
class GtfsStaticLoaderIT {

    @Autowired GtfsStaticLoader loader;
    @Autowired RouteRepository routeRepository;
    @Autowired StopRepository stopRepository;
    @Autowired StopTimeRepository stopTimeRepository;
    @Autowired BranchRepository branchRepository;

    @Test
    void loadsLineIntoDb() throws Exception {
        try (var in = getClass().getResourceAsStream("/gtfs-mini.zip")) {
            loader.loadFromZip(in, "TEST9");
        }
        Route route = routeRepository.findByGtfsId("TEST9").orElseThrow();
        assertThat(route.getSiriLineRef()).isEqualTo("STIF:Line::TEST9:");
        assertThat(branchRepository.findAllWithRoute()).singleElement()
            .satisfies(branch -> assertThat(branch.getGeom().getNumPoints()).isEqualTo(3));
        assertThat(stopRepository.count()).isEqualTo(3);
        assertThat(stopTimeRepository.findAllForRegistry()).hasSize(3);
    }

    @Test
    void loadsOnlyTheRequestedRouteFromAMultiRouteFeed() throws Exception {
        try (var in = getClass().getResourceAsStream("/gtfs-multi.zip")) {
            loader.loadFromZip(in, "TEST9");
        }

        assertThat(routeRepository.findByGtfsId("TEST9")).isPresent();
        assertThat(stopRepository.count()).isEqualTo(3);
        assertThat(branchRepository.count()).isEqualTo(1);
        assertThat(stopTimeRepository.findAllForRegistry()).hasSize(3);

        assertThat(routeRepository.findByGtfsId("TESTX")).isEmpty();
        assertThat(stopRepository.findAll())
            .extracting("gtfsId")
            .containsExactlyInAnyOrder("S1", "S2", "S3");
    }

    @Test
    void usesTheLongestShapeWhenRouteHasSeveralVariants() throws Exception {
        // R1 a 2 trips, un par sens : TA/SH_SHORT (2 points, ~0.001°) et TB/SH_LONG (4 points,
        // ~0.03°). Le tracé retenu doit être le plus long (SH_LONG) pour les DEUX branches, sinon
        // les arrêts hors emprise se projetteraient sur l'extrémité du tracé court.
        try (var in = getClass().getResourceAsStream("/gtfs-twoshapes.zip")) {
            loader.loadFromZip(in, "R1");
        }

        assertThat(branchRepository.findAllWithRoute()).hasSize(2)
            .allSatisfy(branch -> assertThat(branch.getGeom().getNumPoints()).isEqualTo(4))
            .extracting(Branch::getDirection)
            .containsExactly((short) 0, (short) 1);
    }

    @Test
    void readsParentStationFromStopsAndLeavesItNullWhenAbsent() throws Exception {
        try (var in = getClass().getResourceAsStream("/gtfs-parent.zip")) {
            loader.loadFromZip(in, "RP");
        }
        var stops = stopRepository.findAll();
        assertThat(stops).hasSize(5);
        assertThat(stops).filteredOn(s -> s.getGtfsId().equals("PA0"))
            .singleElement().extracting("parentStation").isEqualTo("SAA");
        assertThat(stops).filteredOn(s -> s.getGtfsId().equals("PA1"))
            .singleElement().extracting("parentStation").isEqualTo("SAA");
        assertThat(stops).filteredOn(s -> s.getGtfsId().equals("PC"))
            .singleElement().extracting("parentStation").isNull();
    }
}
