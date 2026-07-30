package com.mapidf.configurations.properties;

import java.util.ArrayList;
import java.util.List;

import com.mapidf.data.enums.TransportMode;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NetworkPropertiesTest {

    @Test
    void normalizesNullModesToAnEmptyList() {
        NetworkProperties properties = new NetworkProperties(null, List.of("IDFM:C01379"));

        assertThat(properties.modes()).isEmpty();
    }

    @Test
    void normalizesNullExcludeToAnEmptyList() {
        NetworkProperties properties = new NetworkProperties(List.of(TransportMode.METRO), null);

        assertThat(properties.exclude()).isEmpty();
    }

    @Test
    void exposedListsAreImmutableCopiesNotAffectedByLaterMutationOfTheSource() {
        List<TransportMode> modes = new ArrayList<>(List.of(TransportMode.METRO));
        List<String> exclude = new ArrayList<>(List.of("IDFM:C01379"));

        NetworkProperties properties = new NetworkProperties(modes, exclude);
        modes.add(TransportMode.TRAM);
        exclude.add("IDFM:AUTRE");

        assertThat(properties.modes()).containsExactly(TransportMode.METRO);
        assertThat(properties.exclude()).containsExactly("IDFM:C01379");
        assertThatThrownBy(() -> properties.modes().add(TransportMode.BUS))
            .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> properties.exclude().add("IDFM:AUTRE"))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void tracksReturnsTrueOnlyForAConfiguredMode() {
        NetworkProperties properties = new NetworkProperties(List.of(TransportMode.METRO), List.of());

        assertThat(properties.tracks(TransportMode.METRO)).isTrue();
        assertThat(properties.tracks(TransportMode.TRAM)).isFalse();
    }

    @Test
    void isExcludedReturnsTrueOnlyForAnExcludedRouteId() {
        NetworkProperties properties = new NetworkProperties(List.of(TransportMode.METRO), List.of("IDFM:C01379"));

        assertThat(properties.isExcluded("IDFM:C01379")).isTrue();
        assertThat(properties.isExcluded("IDFM:AUTRE")).isFalse();
    }
}
