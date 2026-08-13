package com.mapidf.controllers.stations;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import com.mapidf.MapIdfTest;
import com.mapidf.gtfs.GtfsStaticLoader;
import com.mapidf.gtfs.GtfsStaticService;
import com.mapidf.disruptions.DisruptionPoller;
import com.mapidf.rt.RealtimePoller;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@MapIdfTest
class StationsControllerIT {

    @Autowired WebApplicationContext wac;
    @Autowired GtfsStaticLoader loader;
    @Autowired GtfsStaticService staticService;
    @Autowired RealtimePoller poller;
    @Autowired DisruptionPoller disruptionPoller;
    MockMvc mockMvc;

    /**
     * Deux courses au même arrêt de correspondance, une par ligne. Les quais S2 (ligne 9) et P2
     * (ligne 7) de la station STC portent tous deux le stopKey « 2 » : un seul StopPointRef
     * suffit donc à toucher la station par les deux lignes, la répartition par ligne se faisant
     * en amont sur le LineRef du flux.
     *
     * <p>Les heures sont calculées à l'exécution, PAS écrites en dur : le service écarte les
     * passages déjà partis (comparaison à {@code Instant.now()}), donc une date figée dans la
     * fixture rendrait le test vide et vert par accident.
     */
    private static String twoLineSnapshotAt(Instant nine, Instant seven) {
        return """
            {"Siri":{"ServiceDelivery":{"EstimatedTimetableDelivery":[
              {"EstimatedJourneyVersionFrame":[{"EstimatedVehicleJourney":[
                {"LineRef":{"value":"STIF:Line::C01379:"},
                 "DirectionRef":{"value":"0"},
                 "DatedVehicleJourneyRef":{"value":"V9"},
                 "DestinationName":[{"value":"Gamma"}],
                 "EstimatedCalls":{"EstimatedCall":[{
                   "StopPointRef":{"value":"STIF:StopPoint:Q:2:"},
                   "ExpectedArrivalTime":"%s",
                   "DepartureStatus":"ON_TIME"}]}},
                {"LineRef":{"value":"STIF:Line::C01377:"},
                 "DirectionRef":{"value":"0"},
                 "DatedVehicleJourneyRef":{"value":"V7"},
                 "DestinationName":[{"value":"Ivry"}],
                 "EstimatedCalls":{"EstimatedCall":[{
                   "StopPointRef":{"value":"STIF:StopPoint:Q:2:"},
                   "ExpectedArrivalTime":"%s",
                   "DepartureStatus":"DELAYED"}]}}
              ]}]}]}}}
            """.formatted(nine, seven);
    }

    @BeforeEach
    void setup() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).build();
        try (var in = getClass().getResourceAsStream("/gtfs-branch.zip")) {
            loader.load(in);
        }
        staticService.publishFromDatabase();
        poller.pollOnce(url -> new ByteArrayInputStream(
            "{}".getBytes(StandardCharsets.UTF_8)), Instant.now());
    }

    @Test
    void returnsTheStationEnvelopeForACorrespondence() throws Exception {
        mockMvc.perform(get("/stations/STC/departures"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.stationName").value("Correspondance"))
            .andExpect(jsonPath("$.lines").isArray());
    }

    @Test
    void resolvesEveryLineServingTheStation() throws Exception {
        // Le point de jonction introduit en tâche 12 : le contrôleur résout station.lineIds()
        // vers les TrackedLine du registry. Remplacé par une liste vide en dur, il rendrait un
        // payload sans aucune ligne — et les 93 tests d'avant cette vague restaient verts.
        //
        // On injecte donc un snapshot couvrant les DEUX lignes de la correspondance STC. L'ordre
        // attendu est l'ordre humain (7 puis 9, cf. StationDepartureService.sortedByHumanOrder),
        // pas l'ordre des identifiants du registry.
        Instant now = Instant.now();
        poller.pollOnce(url -> new ByteArrayInputStream(
            twoLineSnapshotAt(now.plusSeconds(120), now.plusSeconds(180))
                .getBytes(StandardCharsets.UTF_8)), now);

        mockMvc.perform(get("/stations/STC/departures"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.stationName").value("Correspondance"))
            .andExpect(jsonPath("$.lines", hasSize(2)))
            .andExpect(jsonPath("$.lines[0].lineId").value("7"))
            .andExpect(jsonPath("$.lines[0].color").value("#FF82B4"))
            .andExpect(jsonPath("$.lines[0].directions", hasSize(1)))
            .andExpect(jsonPath("$.lines[0].directions[0].destination").value("Ivry"))
            .andExpect(jsonPath("$.lines[0].directions[0].passages[0].journeyRef").value("V7"))
            .andExpect(jsonPath("$.lines[0].directions[0].passages[0].status").value("DELAYED"))
            .andExpect(jsonPath("$.lines[1].lineId").value("9"))
            .andExpect(jsonPath("$.lines[1].color").value("#D2D200"))
            .andExpect(jsonPath("$.lines[1].directions", hasSize(1)))
            .andExpect(jsonPath("$.lines[1].directions[0].destination").value("Gamma"))
            .andExpect(jsonPath("$.lines[1].directions[0].passages[0].journeyRef").value("V9"));
    }

    /** Quai « 2 » = les deux quais de la station STC ; la ligne 9 est aussi coupée entièrement. */
    private static final String DISRUPTIONS = """
        {
          "disruptions": [
            {"id": "quai", "cause": "TRAVAUX", "severity": "BLOQUANTE",
             "title": "Métro 9 : Travaux - Arrêt non desservi",
             "shortMessage": "Arrêt non desservi",
             "message": "<p>Descendez &#224; la station suivante.</p>",
             "applicationPeriods": [{"begin": "20200101T000000", "end": "20991231T235959"}]},
            {"id": "ligne", "cause": "PERTURBATION", "severity": "PERTURBEE",
             "title": "Métro 9 : Incident", "shortMessage": "Trafic perturbé",
             "applicationPeriods": [{"begin": "20200101T000000", "end": "20991231T235959"}]}
          ],
          "lines": [
            {"id": "line:IDFM:C01379", "shortName": "9", "mode": "Metro", "impactedObjects": [
              {"type": "line", "id": "line:IDFM:C01379", "disruptionIds": ["ligne"]},
              {"type": "stop_point", "id": "stop_point:IDFM:2", "disruptionIds": ["quai"]}
            ]}
          ]
        }
        """;

    @Test
    void carriesTheDisruptionsAimedAtThePlatformsOfThisStation() throws Exception {
        disruptionPoller.pollOnce(url -> new ByteArrayInputStream(
            DISRUPTIONS.getBytes(StandardCharsets.UTF_8)), Instant.now());

        // Seule la perturbation de QUAI remonte : celle de ligne entière vit dans le sélecteur de
        // lignes, la répéter ici noierait une correspondance. Et son détail arrive en texte.
        mockMvc.perform(get("/stations/STC/departures"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.disruptions", hasSize(1)))
            .andExpect(jsonPath("$.disruptions[0].shortMessage").value("Arrêt non desservi"))
            .andExpect(jsonPath("$.disruptions[0].severity").value("BLOQUANTE"))
            .andExpect(jsonPath("$.disruptions[0].detail").value("Descendez à la station suivante."));
    }

    @Test
    void servesAnEmptyDisruptionListWhenThePlatformsAreFine() throws Exception {
        disruptionPoller.pollOnce(url -> new ByteArrayInputStream(
            "{}".getBytes(StandardCharsets.UTF_8)), Instant.now());

        mockMvc.perform(get("/stations/STC/departures"))
            .andExpect(jsonPath("$.disruptions", hasSize(0)));
    }

    @Test
    void returnsNotFoundForAnUnknownStation() throws Exception {
        mockMvc.perform(get("/stations/NOPE/departures"))
            .andExpect(status().isNotFound());
    }

    @Test
    void marksTheResponseAsNeverStorable() throws Exception {
        mockMvc.perform(get("/stations/STC/departures"))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
    }

    @Test
    void servesAFreshPollWithoutWaitingForTheNextSecond() throws Exception {
        // L'endpoint le plus exposé du chantier — trois sources et une clé — était le seul sans
        // preuve d'invalidation de bout en bout. Le setup() a injecté un instantané vide, donc
        // aucune ligne ne dessert encore la station.
        mockMvc.perform(get("/stations/STC/departures"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.lines", hasSize(0)));

        Instant now = Instant.now();
        poller.pollOnce(url -> new ByteArrayInputStream(
            twoLineSnapshotAt(now.plusSeconds(120), now.plusSeconds(180))
                .getBytes(StandardCharsets.UTF_8)), now);

        // Même seconde murale que la requête précédente, selon toute vraisemblance : c'est
        // l'identité de l'instantané, et non le temps, qui doit faire tomber l'entrée.
        mockMvc.perform(get("/stations/STC/departures"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.lines", hasSize(2)));
    }

    @Test
    void keepsStationsIndependentWithinTheSameSecond() throws Exception {
        // Le cache est indexé par station : deux stations interrogées dans la même seconde ne
        // doivent pas se servir la réponse l'une de l'autre.
        mockMvc.perform(get("/stations/STC/departures"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.stationName").value("Correspondance"));

        mockMvc.perform(get("/stations/ST1/departures"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.stationName").value("Alpha"));
    }
}
