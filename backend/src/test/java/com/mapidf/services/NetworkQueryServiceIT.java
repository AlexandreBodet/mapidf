package com.mapidf.services;

import com.mapidf.MapIdfTest;
import com.mapidf.controllers.lines.ShapeResponse;
import com.mapidf.gtfs.GtfsStaticLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

@MapIdfTest
class NetworkQueryServiceIT {

    @Autowired GtfsStaticLoader loader;
    @Autowired NetworkQueryService service;

    @BeforeEach
    void load() throws Exception {
        try (var in = getClass().getResourceAsStream("/gtfs-parent.zip")) {
            loader.loadFromZip(in, "RP");
        }
    }

    @Test
    void groupsPlatformsSharingAParentIntoOneStation() {
        ShapeResponse shape = service.getShape("RP");

        // 5 quais → 3 stations (SAA, SAB, PC seul)
        assertThat(shape.getStops()).hasSize(3);

        ShapeResponse.StopDto alpha = shape.getStops().stream()
            .filter(s -> s.getName().equals("Alpha")).findFirst().orElseThrow();
        assertThat(alpha.getId()).isEqualTo("SAA");
        assertThat(alpha.getPlatformIds()).containsExactlyInAnyOrder("PA0", "PA1");
        assertThat(alpha.getLng()).isCloseTo(2.30005, within(1e-4)); // moyenne des deux quais

        ShapeResponse.StopDto gamma = shape.getStops().stream()
            .filter(s -> s.getName().equals("Gamma")).findFirst().orElseThrow();
        assertThat(gamma.getId()).isEqualTo("PC");           // sans parent → clé = gtfsId
        assertThat(gamma.getPlatformIds()).containsExactly("PC");
    }
}
