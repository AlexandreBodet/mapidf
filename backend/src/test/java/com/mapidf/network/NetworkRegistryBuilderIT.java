package com.mapidf.network;

import com.mapidf.MapIdfTest;
import com.mapidf.gtfs.GtfsStaticLoader;
import com.mapidf.position.StopOnLine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

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
    void buildsTheTrackedLinesWithTheirBranches() {
        NetworkSnapshot snapshot = builder.build();

        // "3b" et non "3B" : le registry est le seul producteur des identifiants publics que
        // voit l'API, il normalise route.short_name (persisté brut) à la construction.
        assertThat(snapshot.lines()).extracting(TrackedLine::id)
            .containsExactlyInAnyOrder("9", "7", "3b");
        assertThat(snapshot.linesById().get("3b").branches()).hasSize(1);
        assertThat(snapshot.linesById().get("9").branches()).hasSize(2);
        assertThat(snapshot.linesById().get("7").branches()).hasSize(2);
        assertThat(snapshot.linesBySiriRef()).containsKey("STIF:Line::C01379:");
    }

    @Test
    void projectsEachBranchStopsOntoItsOwnGeometry() {
        NetworkSnapshot snapshot = builder.build();

        LineBranch villejuif = snapshot.linesById().get("7").branches().stream()
            .filter(b -> b.shapeId().equals("SH7A")).findFirst().orElseThrow();

        // 4 arrêts projetés, distances STRICTEMENT croissantes le long du tracé : cette
        // monotonie est ce qui rend l'interpolation correcte. Avec un tracé unique pour les
        // deux branches, P4 se projetterait à ~1,5 km de sa position réelle. La monotonie doit
        // être stricte (pas isSorted(), qui tolère des égalités) : sous le bug ci-dessous, deux
        // arrêts consécutifs de SH7B se projetteraient à la même distance sur ce tracé.
        assertThat(villejuif.stops()).hasSize(4);
        assertStrictlyIncreasing(villejuif.stops());
        assertThat(villejuif.indexOf("4")).isEqualTo(3);
        assertThat(villejuif.indexOf("5")).isEqualTo(-1);

        // SH7A est la PREMIÈRE branche itérée (findAllWithRoute trie par route.gtfsId, et
        // "IDFM:C01377" < "IDFM:C01379") : un LengthIndexedLine hoisté hors de la boucle (donc
        // partagé entre branches, l'ancien modèle à géométrie unique) laisserait SH7A intacte —
        // elle recevrait par hasard la bonne géométrie. Il faut donc aussi pinner une branche
        // NON première : SH7B, dont le terminus (P5=Ivry) doit se projeter à l'extrémité de SA
        // PROPRE géométrie, pas de celle de SH7A.
        LineBranch ivry = snapshot.linesById().get("7").branches().stream()
            .filter(b -> b.shapeId().equals("SH7B")).findFirst().orElseThrow();

        assertThat(ivry.stops()).hasSize(4);
        assertStrictlyIncreasing(ivry.stops());
        assertThat(ivry.stops().getLast().distanceAlongLine())
            .isCloseTo(ivry.indexed().getEndIndex(), within(1e-9));
        assertThat(ivry.indexOf("5")).isEqualTo(3);
        assertThat(ivry.indexOf("4")).isEqualTo(-1);
    }

    /** Monotonie STRICTE : {@code isSorted()} d'AssertJ tolère des égalités, insuffisant ici. */
    private static void assertStrictlyIncreasing(java.util.List<StopOnLine> stops) {
        for (int i = 1; i < stops.size(); i++) {
            assertThat(stops.get(i).distanceAlongLine())
                .as("distance de l'arrêt %d vs l'arrêt %d", i, i - 1)
                .isGreaterThan(stops.get(i - 1).distanceAlongLine());
        }
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

        // 9 stations : ST1, STC, ST3 (ligne 9) + PT1, PT3, PT4, PT5 (ligne 7), STC partagée,
        // + T31, T32 (ligne 3B).
        assertThat(snapshot.stations()).hasSize(9);

        Station correspondence = snapshot.stationsById().get("STC");
        assertThat(correspondence.name()).isEqualTo("Correspondance");
        assertThat(correspondence.lineIds()).containsExactly("7", "9");
        assertThat(correspondence.platformIds()).containsExactlyInAnyOrder("S2", "P2");
    }

    @Test
    void takesStationCoordinatesFromTheParentStop() {
        NetworkSnapshot snapshot = builder.build();

        // Dans la fixture, le parent STC (48.8503/2.3107) a des coordonnées DISTINCTES de
        // celles de ses deux quais (S2 à 48.8501/2.3101, P2 à 48.8499/2.3099) : sans cet écart,
        // une implémentation qui reprendrait par erreur les coordonnées d'un quai (ou leur
        // centroïde, ~48.8500/2.3100) passerait quand même le test. C'est la même raison qui
        // fait que le nom du quai diffère aussi du nom du parent (« Correspondance quai 9/7 »
        // contre « Correspondance ») : le nom déterministe est le point même du ticket fermé
        // par cette tâche.
        Station correspondence = snapshot.stationsById().get("STC");
        assertThat(correspondence.name()).isEqualTo("Correspondance");
        assertThat(correspondence.lat()).isEqualTo(48.8503);
        assertThat(correspondence.lng()).isEqualTo(2.3107);
    }
}
