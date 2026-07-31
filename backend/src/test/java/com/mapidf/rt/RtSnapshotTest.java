package com.mapidf.rt;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class RtSnapshotTest {

    @Test
    void hasNoDataDateBeforeTheFirstSuccessfulPoll() {
        // Servi tel quel, EPOCH s'afficherait comme « données du 01:00:00 » — une date de mise à
        // jour fausse, ce que l'art. 5.7 de la Licence Mobilité interdit explicitement.
        assertThat(RtSnapshot.empty().dataDate()).isNull();
    }

    @Test
    void datesTheDataAtTheInstantItWasPolled() {
        Instant polled = Instant.parse("2026-07-31T09:15:00Z");

        assertThat(new RtSnapshot(polled, Map.of()).dataDate()).isEqualTo(polled);
    }
}
