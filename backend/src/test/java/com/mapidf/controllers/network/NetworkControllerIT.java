package com.mapidf.controllers.network;

import com.mapidf.MapIdfTest;
import com.mapidf.gtfs.GtfsStaticLoader;
import com.mapidf.gtfs.GtfsStaticService;
import com.mapidf.network.LineRegistry;
import com.mapidf.network.NetworkSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.assertj.core.api.Assertions.assertThat;

@MapIdfTest
class NetworkControllerIT {

    @Autowired WebApplicationContext wac;
    @Autowired GtfsStaticLoader loader;
    @Autowired GtfsStaticService staticService;
    @Autowired LineRegistry registry;
    MockMvc mockMvc;

    private static final ObjectMapper JSON = new ObjectMapper();

    @BeforeEach
    void setup() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).build();
        try (var in = getClass().getResourceAsStream("/gtfs-branch.zip")) {
            loader.load(in);
        }
        staticService.publishFromDatabase();
    }

    @Test
    void returnsTheTrackedLines() throws Exception {
        mockMvc.perform(get("/network"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.lines", hasSize(2)))
            .andExpect(jsonPath("$.lines[*].id", containsInAnyOrder("7", "9")))
            .andExpect(jsonPath("$.lines[?(@.id == '7')].color").value("#FF82B4"))
            .andExpect(jsonPath("$.lines[?(@.id == '7')].mode").value("METRO"));
    }

    @Test
    void returnsOnePolylinePerBranch() throws Exception {
        // 4 branches : SH9 + SH9R pour la 9, SH7A + SH7B pour la 7.
        MvcResult result = mockMvc.perform(get("/network"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.shapes", hasSize(4)))
            .andExpect(jsonPath("$.shapes[?(@.terminusName == 'Villejuif')].coordinates", hasSize(1)))
            .andReturn();

        // Pouvoir discriminant : hasSize(4) seul passerait même si les 4 branches
        // renvoyaient quatre fois la même géométrie (un bug déjà rencontré sur ce
        // chantier : la géométrie d'une seule branche recopiée pour toutes). SH7A
        // (Villejuif) et SH7B (Ivry) partagent leurs 3 premiers points dans la fixture
        // et ne divergent qu'au dernier — on vérifie donc ce point précis pour prouver
        // que chaque shape porte bien SA propre géométrie de branche, et pas une autre.
        JsonNode root = JSON.readTree(result.getResponse().getContentAsString());
        JsonNode villejuifCoords = findShapeByTerminus(root, "Villejuif").get("coordinates");
        JsonNode ivryCoords = findShapeByTerminus(root, "Ivry").get("coordinates");

        JsonNode villejuifLast = villejuifCoords.get(villejuifCoords.size() - 1);
        JsonNode ivryLast = ivryCoords.get(ivryCoords.size() - 1);

        assertThat(villejuifLast.get(0).asDouble()).isEqualTo(2.300);
        assertThat(villejuifLast.get(1).asDouble()).isEqualTo(48.830);
        assertThat(ivryLast.get(0).asDouble()).isEqualTo(2.320);
        assertThat(ivryLast.get(1).asDouble()).isEqualTo(48.830);
        assertThat(villejuifLast).isNotEqualTo(ivryLast);
    }

    @Test
    void returnsStationsDeduplicatedWithTheirLines() throws Exception {
        mockMvc.perform(get("/network"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.stations", hasSize(7)))
            .andExpect(jsonPath("$.stations[?(@.id == 'STC')].name").value("Correspondance"))
            .andExpect(jsonPath("$.stations[?(@.id == 'STC')].lineIds[*]",
                containsInAnyOrder("7", "9")));
    }

    @Test
    void allowsBrowserCachingOfTheStaticNetwork() throws Exception {
        // Le réseau ne change qu'au rechargement GTFS (une fois par jour).
        mockMvc.perform(get("/network"))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "max-age=600, public"));
    }

    @Test
    void neverCachesTheEmptyNetworkOfAColdStart() throws Exception {
        // Cas certain au premier déploiement de la branche : après la migration V4 la base est
        // vide, hydrateOnStartup publie un snapshot vide sans lever, et /network répond 200 avec
        // {lines:[],shapes:[],stations:[]}. Cachée 10 min en public, cette carte vide survivrait
        // au retour à la normale du backend et se propagerait par tout proxy intermédiaire.
        registry.publish(NetworkSnapshot.empty());

        mockMvc.perform(get("/network"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.lines", hasSize(0)))
            .andExpect(header().string("Cache-Control", "no-store"));
    }

    private static JsonNode findShapeByTerminus(JsonNode root, String terminusName) {
        for (JsonNode shape : root.get("shapes")) {
            if (terminusName.equals(shape.get("terminusName").asString())) {
                return shape;
            }
        }
        throw new AssertionError("Aucune shape avec terminusName=" + terminusName);
    }
}
