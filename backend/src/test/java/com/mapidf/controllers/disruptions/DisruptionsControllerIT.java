package com.mapidf.controllers.disruptions;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import com.mapidf.MapIdfTest;
import com.mapidf.disruptions.DisruptionPoller;
import com.mapidf.gtfs.GtfsStaticLoader;
import com.mapidf.gtfs.GtfsStaticService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@MapIdfTest
class DisruptionsControllerIT {

    @Autowired WebApplicationContext wac;
    @Autowired GtfsStaticLoader loader;
    @Autowired GtfsStaticService staticService;
    @Autowired DisruptionPoller poller;
    MockMvc mockMvc;

    // Périodes volontairement très larges (ou entièrement passées) : l'IT tourne à une heure
    // quelconque, et c'est le filtre « en cours » qu'on veut éprouver, pas l'horloge.
    private static final String FEED = """
        {
          "disruptions": [
            {"id": "en-cours", "cause": "PERTURBATION", "severity": "PERTURBEE",
             "title": "Métro 9 : Incident - Trafic perturbé", "shortMessage": "Trafic perturbé",
             "message": "<p>NE-DOIT-PAS-SORTIR</p>",
             "applicationPeriods": [{"begin": "20200101T000000", "end": "20991231T235959"}]},
            {"id": "pire", "cause": "TRAVAUX", "severity": "BLOQUANTE",
             "title": "Métro 9 : Travaux - Trafic interrompu", "shortMessage": "Trafic interrompu",
             "applicationPeriods": [{"begin": "20200101T000000", "end": "20991231T235959"}]},
            {"id": "terminee", "cause": "TRAVAUX", "severity": "BLOQUANTE",
             "title": "Métro 7 : Travaux terminés", "shortMessage": "Terminé",
             "applicationPeriods": [{"begin": "20200101T000000", "end": "20200102T000000"}]}
          ],
          "lines": [
            {"id": "line:IDFM:C01379", "shortName": "9", "mode": "Metro", "impactedObjects": [
              {"type": "line", "id": "line:IDFM:C01379", "disruptionIds": ["en-cours", "pire"]}
            ]},
            {"id": "line:IDFM:C01377", "shortName": "7", "mode": "Metro", "impactedObjects": [
              {"type": "line", "id": "line:IDFM:C01377", "disruptionIds": ["terminee"]}
            ]}
          ]
        }
        """;

    @BeforeEach
    void setup() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).build();
        try (var in = getClass().getResourceAsStream("/gtfs-branch.zip")) {
            loader.load(in);
        }
        staticService.publishFromDatabase();
        poller.pollOnce(url -> new ByteArrayInputStream(
            FEED.getBytes(StandardCharsets.UTF_8)), Instant.now());
    }

    @Test
    void servesOnlyTheLinesDisruptedRightNow() throws Exception {
        // La 7 n'a qu'une perturbation terminée en 2020 : elle ne doit pas apparaître du tout.
        mockMvc.perform(get("/disruptions"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.lines", hasSize(1)))
            .andExpect(jsonPath("$.lines[0].lineId").value("9"))
            .andExpect(jsonPath("$.lines[0].items", hasSize(2)));
    }

    @Test
    void reportsTheWorstSeverityOfTheLine() throws Exception {
        mockMvc.perform(get("/disruptions"))
            .andExpect(jsonPath("$.lines[0].severity").value("BLOQUANTE"))
            .andExpect(jsonPath("$.lines[0].items[0].title")
                .value(containsString("Trafic interrompu")));
    }

    @Test
    void neverExposesTheHtmlMessageOfTheFeed() throws Exception {
        // Le flux sert du HTML tiers dans `message` : le rendre serait une faille XSS, donc il
        // ne doit même pas franchir l'API.
        mockMvc.perform(get("/disruptions"))
            .andExpect(content().string(not(containsString("NE-DOIT-PAS-SORTIR"))))
            .andExpect(content().string(not(containsString("<p>"))));
    }

    @Test
    void answersWithAnEmptyListWhenNothingIsDisrupted() throws Exception {
        poller.pollOnce(url -> new ByteArrayInputStream(
            "{}".getBytes(StandardCharsets.UTF_8)), Instant.now());

        mockMvc.perform(get("/disruptions"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.lines", hasSize(0)));
    }
}
