package com.mapidf.gtfs;

import com.mapidf.MapIdfTest;
import com.mapidf.data.entity.Branch;
import com.mapidf.data.repositories.BranchRepository;
import com.mapidf.data.repositories.RouteRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code app.network.exclude} est le seul remède prévu au risque n°1 de la spec — une ligne au
 * référentiel atypique qu'on veut écarter en attendant de la traiter. Il n'était exercé par aucun
 * test : supprimer le {@code if (network.isExcluded(routeId)) continue;} du loader laissait toute
 * la suite verte.
 *
 * <p>Classe séparée parce que la clé est une propriété de contexte : la surcharger ici évite de
 * la surcharger pour les autres IT, qui chargent la même fixture sans exclusion.
 */
@MapIdfTest
@TestPropertySource(properties = "app.network.exclude=IDFM:C01377")
class GtfsStaticLoaderExcludeIT {

    @Autowired GtfsStaticLoader loader;
    @Autowired RouteRepository routeRepository;
    @Autowired BranchRepository branchRepository;

    @Test
    void skipsAnExcludedRouteEvenWhenItsModeIsTracked() throws Exception {
        try (var in = getClass().getResourceAsStream("/gtfs-branch.zip")) {
            loader.load(in);
        }

        // La 7 est bien du mode suivi (route_type=1) et a deux branches dans la fixture : seule
        // l'exclusion peut la faire disparaître. Ses deux tracés partent avec elle.
        assertThat(routeRepository.findByGtfsId("IDFM:C01377")).isEmpty();
        assertThat(branchRepository.findAllWithRoute()).extracting(Branch::getGtfsShapeId)
            .containsExactlyInAnyOrder("SH9", "SH9R", "SH3B");

        // Les autres lignes du mode suivi restent chargées : l'exclusion est ciblée, pas globale.
        assertThat(routeRepository.findByGtfsId("IDFM:C01379")).isPresent();
        assertThat(routeRepository.findByGtfsId("IDFM:C01386")).isPresent();
    }
}
