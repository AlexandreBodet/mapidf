package com.mapidf.network;

import java.util.List;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class NetworkSnapshotTest {

    private static TrackedLine line(String id, String siriRef) {
        return new TrackedLine(id, "IDFM:C0" + id, siriRef, id, "#000000", "METRO", List.of());
    }

    @Test
    void indexesLinesByPublicIdAndBySiriRef() {
        NetworkSnapshot snapshot = NetworkSnapshot.of(
            List.of(line("9", "STIF:Line::C01379:"), line("7", "STIF:Line::C01377:")),
            List.of());

        assertThat(snapshot.linesById().get("9").siriLineRef()).isEqualTo("STIF:Line::C01379:");
        assertThat(snapshot.linesBySiriRef().get("STIF:Line::C01377:").id()).isEqualTo("7");
    }

    @Test
    void indexesStationsById() {
        Station station = new Station("STC", "Correspondance", 48.850, 2.310,
            List.of("S2", "P2"), List.of("7", "9"));

        NetworkSnapshot snapshot = NetworkSnapshot.of(List.of(), List.of(station));

        assertThat(snapshot.stationsById().get("STC").lineIds()).containsExactly("7", "9");
    }

    @Test
    void emptySnapshotHasNoLineAndNoStation() {
        assertThat(NetworkSnapshot.empty().lines()).isEmpty();
        assertThat(NetworkSnapshot.empty().stations()).isEmpty();
        assertThat(NetworkSnapshot.empty().linesById()).isEmpty();
    }
}
