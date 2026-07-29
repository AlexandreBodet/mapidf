package com.mapidf.gtfs;

import java.util.List;

import com.mapidf.MapIdfTest;
import com.mapidf.data.entity.Branch;
import com.mapidf.data.entity.Route;
import com.mapidf.data.entity.Stop;
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
            loader.load(in);
        }
        Route route = routeRepository.findByGtfsId("TEST9").orElseThrow();
        assertThat(route.getSiriLineRef()).isEqualTo("STIF:Line::TEST9:");
        assertThat(branchRepository.findAllWithRoute()).singleElement()
            .satisfies(branch -> assertThat(branch.getGeom().getNumPoints()).isEqualTo(3));
        assertThat(stopRepository.count()).isEqualTo(3);
        assertThat(stopTimeRepository.findAllForRegistry()).hasSize(3);
    }

    @Test
    void loadsOnlyTheRoutesOfTheTrackedModes() throws Exception {
        try (var in = getClass().getResourceAsStream("/gtfs-multi.zip")) {
            loader.load(in);
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
    void givesEachDirectionTheShapeOfItsOwnRepresentativeTrip() throws Exception {
        // R1 a 2 trips, un par sens : TA/SH_SHORT (2 points, ~0.001°) et TB/SH_LONG (4 points,
        // ~0.03°). Chaque branche porte désormais SON tracé, celui de sa course représentative.
        // Le critère « le tracé le plus long pour tout le monde » est abandonné : il projetait
        // les arrêts d'une branche sur le tracé d'une autre (1547 m d'erreur sur la ligne 7).
        try (var in = getClass().getResourceAsStream("/gtfs-twoshapes.zip")) {
            loader.load(in);
        }

        List<Branch> branches = branchRepository.findAllWithRoute();
        assertThat(branches).hasSize(2);
        assertThat(branches).extracting(Branch::getDirection).containsExactly((short) 0, (short) 1);
        assertThat(branches).extracting(Branch::getGtfsShapeId).containsExactly("SH_SHORT", "SH_LONG");
        assertThat(branches).extracting(branch -> branch.getGeom().getNumPoints()).containsExactly(2, 4);
    }

    @Test
    void readsParentStationFromStopsAndLeavesItNullWhenAbsent() throws Exception {
        try (var in = getClass().getResourceAsStream("/gtfs-parent.zip")) {
            loader.load(in);
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

    @Test
    void keepsOneBranchPerCoveringShapeAndDropsPartialServices() throws Exception {
        try (var in = getClass().getResourceAsStream("/gtfs-branch.zip")) {
            loader.load(in);
        }

        // Ligne 9 : SH9 (3 arrêts) couvre S1,S2,S3 ; SH9S (S1,S2) est un service partiel
        // inclus, donc écarté. Sens 1 : SH9R. => 2 branches.
        // Ligne 7 : SH7A (P1..P4) et SH7B (P1,P2,P3,P5) apportent chacune un arrêt propre.
        // => 2 branches. Ligne 3B : SH3B seule. Total 5.
        assertThat(branchRepository.findAllWithRoute()).hasSize(5);
        assertThat(branchRepository.findAllWithRoute()).extracting(Branch::getGtfsShapeId)
            .containsExactlyInAnyOrder("SH9", "SH9R", "SH7A", "SH7B", "SH3B");
    }

    @Test
    void derivesTerminusNameFromTheLastServedStopNotTheHeadsign() throws Exception {
        try (var in = getClass().getResourceAsStream("/gtfs-branch.zip")) {
            loader.load(in);
        }
        // T9 porte volontairement le headsign "Mairie de Montreuil" alors que son dernier arrêt est
        // S3/Gamma : c'est le nom de l'ARRÊT qui doit gagner. Sans cet écart dans la fixture, les
        // deux règles coïncideraient et le test ne prouverait rien. Le terminus départage deux
        // branches d'un même sens face au DestinationName du flux temps réel, qui nomme un arrêt.
        List<Branch> branches = branchRepository.findAllWithRoute();
        // Ordre de findAllWithRoute : route.gtfsId, puis direction, puis shapeId — donc
        // C01377 (7), C01379 (9), C01386 (3B).
        assertThat(branches).extracting(Branch::getGtfsShapeId)
            .containsExactly("SH7A", "SH7B", "SH9", "SH9R", "SH3B");
        assertThat(branches).extracting(Branch::getTerminusName)
            .containsExactly("Villejuif", "Ivry", "Gamma", "Alpha", "Gambetta");
    }

    @Test
    void ignoresRoutesOutsideTheTrackedModes() throws Exception {
        try (var in = getClass().getResourceAsStream("/gtfs-branch.zip")) {
            loader.load(in);
        }
        // route_type=3 (bus) n'est pas dans app.network.modes (METRO en profil test).
        assertThat(routeRepository.findByGtfsId("IDFM:C09999")).isEmpty();
        assertThat(stopRepository.findAll()).extracting(Stop::getGtfsId)
            .doesNotContain("B1", "B2");
    }

    @Test
    void persistsOnlyTheStopTimesOfTheRetainedBranches() throws Exception {
        try (var in = getClass().getResourceAsStream("/gtfs-branch.zip")) {
            loader.load(in);
        }
        // 3 (SH9) + 3 (SH9R) + 4 (SH7A) + 4 (SH7B) + 2 (SH3B) = 16. Les 2 lignes de T9S et les
        // 2 du bus ne sont pas matérialisées : c'est ce qui fait passer le métro réel de
        // 941 959 à 915.
        assertThat(stopTimeRepository.findAllForRegistry()).hasSize(16);
    }

    @Test
    void persistsParentStationsAsTheirOwnStops() throws Exception {
        try (var in = getClass().getResourceAsStream("/gtfs-branch.zip")) {
            loader.load(in);
        }
        // 10 quais métro (S1..S3, P1..P5, Q31, Q32) + 9 stations parentes = 19. Les parents
        // portent leur propre nom et leurs propres coordonnées : c'est ce qui rend le nom de
        // station déterministe sur une correspondance.
        assertThat(stopRepository.count()).isEqualTo(19);
        assertThat(stopRepository.findAll()).filteredOn(stop -> stop.getGtfsId().equals("STC"))
            .singleElement().extracting(Stop::getName).isEqualTo("Correspondance");
        assertThat(stopRepository.findAll())
            .filteredOn(stop -> "STC".equals(stop.getParentStation()))
            .extracting(Stop::getGtfsId).containsExactlyInAnyOrder("S2", "P2");
    }

    @Test
    void derivesRouteMetadataFromTheFeed() throws Exception {
        try (var in = getClass().getResourceAsStream("/gtfs-branch.zip")) {
            loader.load(in);
        }
        Route seven = routeRepository.findByGtfsId("IDFM:C01377").orElseThrow();
        assertThat(seven.getShortName()).isEqualTo("7");
        assertThat(seven.getColor()).isEqualTo("#FF82B4");
        assertThat(seven.getSiriLineRef()).isEqualTo("STIF:Line::C01377:");
        assertThat(seven.getMode()).isEqualTo("METRO");
    }

    @Test
    void projectsBranchStopsOntoTheirOwnShape() throws Exception {
        try (var in = getClass().getResourceAsStream("/gtfs-branch.zip")) {
            loader.load(in);
        }
        // Chaque branche porte SES arrêts : P4 appartient à SH7A, P5 à SH7B. Avec un tracé
        // unique, l'un des deux se projetterait à ~1,5 km de sa position réelle.
        assertThat(stopTimeRepository.findByShapeId("SH7A"))
            .extracting(st -> st.getStop().getGtfsId())
            .containsExactly("P1", "P2", "P3", "P4");
        assertThat(stopTimeRepository.findByShapeId("SH7B"))
            .extracting(st -> st.getStop().getGtfsId())
            .containsExactly("P1", "P2", "P3", "P5");
    }
}
