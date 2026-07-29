package com.mapidf.gtfs;

import com.mapidf.data.enums.TransportMode;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class LineDescriptorTest {

    @Test
    void derivesTheSiriLineRefFromTheGtfsRouteId() {
        // Dérivation vérifiée le 2026-07-29 sur les 16 lignes de métro : toutes présentes
        // dans le flux estimated-timetable avec ce LineRef.
        LineDescriptor nine = LineDescriptor.of("IDFM:C01379", "9", "D2D200", TransportMode.METRO);
        assertThat(nine.siriLineRef()).isEqualTo("STIF:Line::C01379:");
    }

    @Test
    void keepsTheLastSegmentOfTheRouteIdAsTheSiriCode() {
        // Un route_id à segments supplémentaires ne doit pas casser la dérivation.
        LineDescriptor line = LineDescriptor.of("IDFM:Line:C01377", "7", "FF82B4", TransportMode.METRO);
        assertThat(line.siriLineRef()).isEqualTo("STIF:Line::C01377:");
    }

    @Test
    void normalisesThePublicIdToLowercaseWithoutSpaces() {
        // Le GTFS écrit "3B" et "7B" ; les URL publiques doivent être /lines/3b et /lines/7b.
        assertThat(LineDescriptor.of("IDFM:C01386", "3B", "82C8E6", TransportMode.METRO).id())
            .isEqualTo("3b");
        assertThat(LineDescriptor.of("IDFM:C01387", " 7B ", "82DC73", TransportMode.METRO).id())
            .isEqualTo("7b");
        assertThat(LineDescriptor.of("IDFM:C01384", "14", "640082", TransportMode.METRO).id())
            .isEqualTo("14");
    }

    @Test
    void normalisesTheColorToCss() {
        // route_color GTFS est un hex SANS '#' ; MapLibre rejette la couche sans le '#'.
        assertThat(LineDescriptor.of("IDFM:C01379", "9", "D2D200", TransportMode.METRO).color())
            .isEqualTo("#D2D200");
        assertThat(LineDescriptor.of("IDFM:C01379", "9", "#D2D200", TransportMode.METRO).color())
            .isEqualTo("#D2D200");
    }

    @Test
    void fallsBackToBlackWhenTheColorIsMissing() {
        assertThat(LineDescriptor.of("IDFM:C01379", "9", null, TransportMode.METRO).color())
            .isEqualTo("#000000");
        assertThat(LineDescriptor.of("IDFM:C01379", "9", "  ", TransportMode.METRO).color())
            .isEqualTo("#000000");
    }

    @Test
    void keepsTheShortNameUntouchedForDisplay() {
        assertThat(LineDescriptor.of("IDFM:C01386", "3B", "82C8E6", TransportMode.METRO).shortName())
            .isEqualTo("3B");
    }
}
