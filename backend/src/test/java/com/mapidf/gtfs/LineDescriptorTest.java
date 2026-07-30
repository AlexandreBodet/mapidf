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
        // Le GTFS écrit "3B" et "7B" ; l'identifiant public est "3b" et "7b". Ce n'est plus un
        // espace d'URL (les endpoints /lines/{id} ont disparu en tâche 4) mais la CLÉ DE JOINTURE
        // entre /network, /vehicles, /stations/{id}/departures, le filtre client et les compteurs
        // du sélecteur.
        //
        // C'est bien la règle en vigueur en production qui est testée ici : NetworkRegistryBuilder
        // appelle cette même méthode statique au build du registry (elle ne vit plus en double).
        assertThat(LineDescriptor.publicId("3B")).isEqualTo("3b");
        assertThat(LineDescriptor.publicId(" 7B ")).isEqualTo("7b");
        assertThat(LineDescriptor.publicId("14")).isEqualTo("14");
        assertThat(LineDescriptor.publicId(null)).isEmpty();
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
