package com.mapidf.gtfs;

import com.mapidf.MapIdfTest;
import com.mapidf.network.LineRegistry;
import com.mapidf.network.NetworkSnapshot;
import com.mapidf.network.TrackedLine;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import static org.assertj.core.api.Assertions.assertThat;

@MapIdfTest
class GtfsStaticServiceIT {

    @Autowired GtfsStaticLoader loader;
    @Autowired GtfsStaticService service;
    @Autowired LineRegistry registry;

    @Test
    void republishesTheRegistryFromTheDatabaseWithoutNetworkAccess() throws Exception {
        try (var in = getClass().getResourceAsStream("/gtfs-branch.zip")) {
            loader.load(in);
        }

        service.publishFromDatabase();

        // C'est le chemin emprunté à chaque redémarrage, sans retélécharger les 109 Mo.
        assertThat(registry.current().lines()).extracting(TrackedLine::id)
            .containsExactlyInAnyOrder("7", "9", "3b");
        assertThat(registry.trackedSiriLineRefs())
            .contains("STIF:Line::C01379:", "STIF:Line::C01377:");
    }

    @Test
    void refreshLeavesTheRegistryUntouchedWhenNoStaticUrlIsConfigured() throws Exception {
        try (var in = getClass().getResourceAsStream("/gtfs-branch.zip")) {
            loader.load(in);
        }
        service.publishFromDatabase();
        NetworkSnapshot before = registry.current();

        // Profil test : app.prim.gtfs-static-url est vide, refresh() doit sortir immédiatement
        // sans lever, sans accès réseau et SANS republier.
        service.refresh();

        assertThat(registry.current()).isSameAs(before);
    }
}
