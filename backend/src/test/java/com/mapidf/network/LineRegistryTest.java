package com.mapidf.network;

import java.util.List;

import com.mapidf.data.enums.ErrorCode;
import com.mapidf.exceptions.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LineRegistryTest {

    private static final TrackedLine LINE_9 =
        new TrackedLine("9", "IDFM:C01379", "STIF:Line::C01379:", "9", "#D2D200", "METRO", List.of());
    private static final Station STATION_STC =
        new Station("STC", "Correspondance", 48.8503, 2.3107, List.of("S2", "P2"), List.of("7", "9"));

    @Test
    void resolvesALineAndAStationOnceThePublishedSnapshotContainsThem() {
        LineRegistry registry = new LineRegistry();
        registry.publish(NetworkSnapshot.of(List.of(LINE_9), List.of(STATION_STC)));

        assertThat(registry.requireLine("9")).isEqualTo(LINE_9);
        assertThat(registry.requireStation("STC")).isEqualTo(STATION_STC);
        assertThat(registry.trackedSiriLineRefs()).containsExactly("STIF:Line::C01379:");
    }

    @Test
    void rejectsAnUnknownLineWith404() {
        LineRegistry registry = new LineRegistry();
        registry.publish(NetworkSnapshot.empty());

        assertThatThrownBy(() -> registry.requireLine("9"))
            .isInstanceOf(ApiException.class)
            .satisfies(ex -> {
                ApiException apiException = (ApiException) ex;
                assertThat(apiException.getHttpStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                assertThat(apiException.getErrorCode()).isEqualTo(ErrorCode.LINE_NOT_FOUND);
            });
    }

    @Test
    void rejectsAnUnknownStationWith404() {
        LineRegistry registry = new LineRegistry();
        registry.publish(NetworkSnapshot.empty());

        assertThatThrownBy(() -> registry.requireStation("STC"))
            .isInstanceOf(ApiException.class)
            .satisfies(ex -> {
                ApiException apiException = (ApiException) ex;
                assertThat(apiException.getHttpStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                assertThat(apiException.getErrorCode()).isEqualTo(ErrorCode.STATION_NOT_FOUND);
            });
    }
}
