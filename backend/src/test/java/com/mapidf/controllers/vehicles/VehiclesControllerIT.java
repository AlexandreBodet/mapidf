package com.mapidf.controllers.vehicles;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

import com.mapidf.MapIdfTest;
import com.mapidf.gtfs.GtfsStaticLoader;
import com.mapidf.gtfs.GtfsStaticService;
import com.mapidf.rt.RealtimePoller;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@MapIdfTest
class VehiclesControllerIT {

    @Autowired WebApplicationContext wac;
    @Autowired GtfsStaticLoader loader;
    @Autowired GtfsStaticService staticService;
    @Autowired RealtimePoller poller;
    MockMvc mockMvc;

    private static final ObjectMapper JSON = new ObjectMapper();
    // Généreux à dessein : le but est de détecter un asOf figé/absent (ex. Instant.EPOCH), pas
    // de mesurer la latence de la suite de tests — un seuil serré rendrait le test intermittent
    // sur une machine chargée.
    private static final Duration FRESHNESS_TOLERANCE = Duration.ofSeconds(30);

    // Arrêt Q:3: pour la 9 (S3 "Gamma", partagé par SH9 et SH9R : le départage se fait par
    // DestinationName="Gamma" == terminus de SH9, pas par unicité de l'arrêt) et Q:4: pour la 7
    // (P4 "Villejuif", propre à la seule branche SH7A). Une seule EstimatedCall chacune donc
    // confidence APPROXIMATE des deux côtés.
    private static final String TWO_LINE_SNAPSHOT = """
        {"Siri":{"ServiceDelivery":{"ResponseTimestamp":"2026-07-29T08:00:00.000Z",
          "EstimatedTimetableDelivery":[{"EstimatedJourneyVersionFrame":[{
            "EstimatedVehicleJourney":[
              {"RecordedAtTime":"2026-07-29T08:00:00.000Z",
               "LineRef":{"value":"STIF:Line::C01379:"},
               "DirectionRef":{"value":"0"},
               "DatedVehicleJourneyRef":{"value":"V9"},
               "DestinationName":[{"value":"Gamma"}],
               "EstimatedCalls":{"EstimatedCall":[{
                 "StopPointRef":{"value":"STIF:StopPoint:Q:3:"},
                 "ExpectedDepartureTime":"2026-07-29T09:00:00.000Z",
                 "DepartureStatus":"ON_TIME"}]}},
              {"RecordedAtTime":"2026-07-29T08:00:00.000Z",
               "LineRef":{"value":"STIF:Line::C01377:"},
               "DirectionRef":{"value":"0"},
               "DatedVehicleJourneyRef":{"value":"V7"},
               "DestinationName":[{"value":"Villejuif"}],
               "EstimatedCalls":{"EstimatedCall":[{
                 "StopPointRef":{"value":"STIF:StopPoint:Q:4:"},
                 "ExpectedDepartureTime":"2026-07-29T09:00:00.000Z",
                 "DepartureStatus":"ON_TIME"}]}}
            ]}]}]
        }}}
        """;

    @BeforeEach
    void setup() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).build();
        try (var in = getClass().getResourceAsStream("/gtfs-branch.zip")) {
            loader.load(in);
        }
        staticService.publishFromDatabase();
        // Snapshot vide déterministe : "{}" est un JSON valide sans EstimatedVehicleJourney,
        // donc 0 course — sans dépendre du test précédent ni d'un appel PRIM. Un corps
        // réellement vide serait risqué : si le parse levait, pollOnce conserverait par
        // conception le snapshot précédent et le test deviendrait dépendant de l'ordre.
        poller.pollOnce(url -> new ByteArrayInputStream(
            "{}".getBytes(StandardCharsets.UTF_8)), Instant.now());
    }

    @Test
    void returnsAnEnvelopeCoveringTheWholeTrackedNetwork() throws Exception {
        // Le poller n'a rien ingéré en profil test (realtime-base-url vide), donc la liste est
        // vide — mais l'endpoint doit répondre 200 avec une enveloppe complète et un asOf frais
        // (proche de l'instant de la requête, pas une valeur figée ou absente), et surtout NE
        // PAS lever alors que le registry contient deux lignes et quatre branches.
        String body = mockMvc.perform(get("/vehicles"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.vehicles").isArray())
            .andExpect(jsonPath("$.vehicles", hasSize(0)))
            .andReturn().getResponse().getContentAsString();

        JsonNode root = JSON.readTree(body);
        Instant asOf = Instant.parse(root.path("asOf").asString());
        assertThat(Duration.between(asOf, Instant.now()).abs())
            .isLessThan(FRESHNESS_TOLERANCE);
    }

    @Test
    void placesTheJourneysOfEveryTrackedLine() throws Exception {
        // Sans donnée temps réel, /vehicles est structurellement incapable de renvoyer un
        // véhicule : on injecte donc un snapshot couvrant les DEUX lignes de la fixture, pour
        // vérifier que le contrôleur balaie bien tout le réseau et non une seule ligne.
        poller.pollOnce(url -> new ByteArrayInputStream(
            TWO_LINE_SNAPSHOT.getBytes(StandardCharsets.UTF_8)), Instant.now());

        mockMvc.perform(get("/vehicles"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.vehicles[*].lineId", containsInAnyOrder("7", "9")))
            .andExpect(jsonPath("$.vehicles[*].confidence",
                containsInAnyOrder("APPROXIMATE", "APPROXIMATE")));
    }
}
