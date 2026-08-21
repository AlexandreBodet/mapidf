package com.mapidf.network;

import java.util.List;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class StationSearchTest {

    private static Station station(String name) {
        return new Station("ST", name, 0, 0, List.of(), List.of());
    }

    @Test
    void derivesTheNormalizedNameOnConstruction() {
        assertThat(station("Châtelet").normalizedName()).isEqualTo("chatelet");
    }

    @Test
    void findsAnAccentedNameFromAPlainQuery() {
        List<Station> stations = List.of(station("Châtelet"), station("Nation"));

        assertThat(StationSearch.search(stations, "chatelet", 8))
            .extracting(Station::name).containsExactly("Châtelet");
    }

    @Test
    void isCaseInsensitive() {
        List<Station> stations = List.of(station("Nation"));

        assertThat(StationSearch.search(stations, "NATION", 8))
            .extracting(Station::name).containsExactly("Nation");
    }

    @Test
    void matchesASubstringAnywhereInTheName() {
        List<Station> stations = List.of(station("Gare de Lyon"));

        assertThat(StationSearch.search(stations, "lyon", 8))
            .extracting(Station::name).containsExactly("Gare de Lyon");
    }

    @Test
    void rendersNoResultForABlankQuery() {
        List<Station> stations = List.of(station("Nation"));

        assertThat(StationSearch.search(stations, "", 8)).isEmpty();
        assertThat(StationSearch.search(stations, "   ", 8)).isEmpty();
    }

    @Test
    void rendersNoResultWhenNothingMatches() {
        List<Station> stations = List.of(station("Nation"));

        assertThat(StationSearch.search(stations, "zzz", 8)).isEmpty();
    }

    @Test
    void respectsTheLimit() {
        List<Station> stations =
            List.of(station("Alpha 1"), station("Alpha 2"), station("Alpha 3"));

        assertThat(StationSearch.search(stations, "alpha", 2)).hasSize(2);
    }
}
