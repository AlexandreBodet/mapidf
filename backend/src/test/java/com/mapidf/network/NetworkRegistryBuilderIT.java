package com.mapidf.network;

import com.mapidf.MapIdfTest;
import com.mapidf.gtfs.GtfsStaticLoader;
import com.mapidf.position.StopOnLine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import static org.assertj.core.api.Assertions.assertThat;

@MapIdfTest
class NetworkRegistryBuilderIT {

    @Autowired GtfsStaticLoader loader;
    @Autowired NetworkRegistryBuilder builder;

    @BeforeEach
    void loadFixture() throws Exception {
        try (var in = getClass().getResourceAsStream("/gtfs-branch.zip")) {
            loader.load(in);
        }
    }

    @Test
    void buildsTheTwoTrackedLinesWithTheirBranches() {
        NetworkSnapshot snapshot = builder.build();

        assertThat(snapshot.lines()).extracting(TrackedLine::id)
            .containsExactlyInAnyOrder("9", "7");
        assertThat(snapshot.linesById().get("9").branches()).hasSize(2);
        assertThat(snapshot.linesById().get("7").branches()).hasSize(2);
        assertThat(snapshot.linesBySiriRef()).containsKey("STIF:Line::C01379:");
    }

    @Test
    void projectsEachBranchStopsOntoItsOwnGeometry() {
        NetworkSnapshot snapshot = builder.build();

        LineBranch villejuif = snapshot.linesById().get("7").branches().stream()
            .filter(b -> b.shapeId().equals("SH7A")).findFirst().orElseThrow();

        // 4 arrêts projetés, distances croissantes le long du tracé : cette monotonie est ce
        // qui rend l'interpolation correcte. Avec un tracé unique pour les deux branches, P4
        // se projetterait à ~1,5 km de sa position réelle.
        assertThat(villejuif.stops()).hasSize(4);
        assertThat(villejuif.stops()).extracting(StopOnLine::distanceAlongLine).isSorted();
        assertThat(villejuif.indexOf("4")).isEqualTo(3);
        assertThat(villejuif.indexOf("5")).isEqualTo(-1);
    }

    @Test
    void namesTheTerminusOfEachBranch() {
        NetworkSnapshot snapshot = builder.build();

        assertThat(snapshot.linesById().get("7").branches())
            .extracting(LineBranch::terminusName)
            .containsExactlyInAnyOrder("Villejuif", "Ivry");
    }

    @Test
    void deduplicatesStationsAndListsTheirLines() {
        NetworkSnapshot snapshot = builder.build();

        // 7 stations : ST1, STC, ST3 (ligne 9) + PT1, PT3, PT4, PT5 (ligne 7), STC partagée.
        assertThat(snapshot.stations()).hasSize(7);

        Station correspondence = snapshot.stationsById().get("STC");
        assertThat(correspondence.name()).isEqualTo("Correspondance");
        assertThat(correspondence.lineIds()).containsExactly("7", "9");
        assertThat(correspondence.platformIds()).containsExactlyInAnyOrder("S2", "P2");
    }

    @Test
    void takesStationCoordinatesFromTheParentStop() {
        NetworkSnapshot snapshot = builder.build();

        // Le parent porte ses propres coordonnées : plus de centroïde de quais, et un nom
        // déterministe (il venait du premier quai rencontré, d'où le ticket connu).
        Station correspondence = snapshot.stationsById().get("STC");
        assertThat(correspondence.lat()).isEqualTo(48.850);
        assertThat(correspondence.lng()).isEqualTo(2.310);
    }
}
