package com.mapidf.gtfs;

import java.util.List;

import com.mapidf.gtfs.BranchSelector.Candidate;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class BranchSelectorTest {

    @Test
    void keepsASingleCandidateWhenThereIsOnlyOne() {
        List<Candidate> selected = BranchSelector.select(List.of(
            new Candidate("SH9", "T9", List.of("S1", "S2", "S3"))));

        assertThat(selected).extracting(Candidate::shapeId).containsExactly("SH9");
    }

    @Test
    void dropsACandidateWhoseStopsAreAlreadyCovered() {
        // Cas majoritaire mesuré : 13 des 16 lignes de métro n'ont qu'un tracé retenu par sens,
        // les autres shapes étant des services partiels inclus dans le plus long.
        List<Candidate> selected = BranchSelector.select(List.of(
            new Candidate("SH_FULL", "T_FULL", List.of("S1", "S2", "S3", "S4")),
            new Candidate("SH_PARTIAL", "T_PARTIAL", List.of("S1", "S2"))));

        assertThat(selected).extracting(Candidate::shapeId).containsExactly("SH_FULL");
    }

    @Test
    void keepsASecondCandidateWhenItAddsAtLeastOneStop() {
        // Cas de la ligne 7 : deux branches partageant leur tronc, chacune avec ses arrêts propres.
        List<Candidate> selected = BranchSelector.select(List.of(
            new Candidate("SH_VILLEJUIF", "TA", List.of("P1", "P2", "P3", "P4")),
            new Candidate("SH_IVRY", "TB", List.of("P1", "P2", "P3", "P5"))));

        // L'intention est « les deux branches sont retenues » : l'ordre relatif est incidental
        // ici (à taille égale, le départage par shapeId placerait SH_IVRY en premier), et c'est
        // isDeterministicWhenTwoCandidatesHaveTheSameSize qui couvre l'ordre.
        assertThat(selected).extracting(Candidate::shapeId)
            .containsExactlyInAnyOrder("SH_VILLEJUIF", "SH_IVRY");
        assertThat(selected).flatExtracting(Candidate::stopIds)
            .contains("P4", "P5");
    }

    @Test
    void coversEveryStopOfTheGroup() {
        List<Candidate> candidates = List.of(
            new Candidate("SH_A", "TA", List.of("A", "B", "C")),
            new Candidate("SH_B", "TB", List.of("A", "B", "D")),
            new Candidate("SH_C", "TC", List.of("A", "E")));

        List<Candidate> selected = BranchSelector.select(candidates);

        assertThat(selected).flatExtracting(Candidate::stopIds)
            .containsAll(List.of("A", "B", "C", "D", "E"));
    }

    @Test
    void prefersTheLongestCandidateFirst() {
        List<Candidate> selected = BranchSelector.select(List.of(
            new Candidate("SH_SHORT", "TS", List.of("S1", "S2")),
            new Candidate("SH_LONG", "TL", List.of("S1", "S2", "S3", "S4"))));

        assertThat(selected).extracting(Candidate::shapeId).containsExactly("SH_LONG");
    }

    @Test
    void isDeterministicWhenTwoCandidatesHaveTheSameSize() {
        // Sans départage stable, deux exécutions retiendraient un ordre différent et les
        // assertions des IT deviendraient intermittentes. On départage par shapeId.
        List<Candidate> first = BranchSelector.select(List.of(
            new Candidate("SH_B", "TB", List.of("X", "Y")),
            new Candidate("SH_A", "TA", List.of("X", "Z"))));
        List<Candidate> second = BranchSelector.select(List.of(
            new Candidate("SH_A", "TA", List.of("X", "Z")),
            new Candidate("SH_B", "TB", List.of("X", "Y"))));

        assertThat(first).extracting(Candidate::shapeId).containsExactly("SH_A", "SH_B");
        assertThat(second).extracting(Candidate::shapeId).containsExactly("SH_A", "SH_B");
    }

    @Test
    void returnsAnEmptyListWhenThereIsNoCandidate() {
        assertThat(BranchSelector.select(List.of())).isEmpty();
    }
}
