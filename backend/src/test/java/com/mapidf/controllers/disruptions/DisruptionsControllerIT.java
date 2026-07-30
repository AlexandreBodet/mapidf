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
import static org.hamcrest.Matchers.hasItem;
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
             "applicationPeriods": [{"begin": "20200101T000000", "end": "20200102T000000"}]},
            {"id": "quai", "cause": "TRAVAUX", "severity": "PERTURBEE",
             "title": "Métro 7 : Travaux - Arrêt non desservi",
             "shortMessage": "Arrêt non desservi",
             "applicationPeriods": [{"begin": "20200101T000000", "end": "20991231T235959"}]}
          ],
          "lines": [
            {"id": "line:IDFM:C01379", "shortName": "9", "mode": "Metro", "impactedObjects": [
              {"type": "line", "id": "line:IDFM:C01379", "disruptionIds": ["en-cours", "pire"]}
            ]},
            {"id": "line:IDFM:C01377", "shortName": "7", "mode": "Metro", "impactedObjects": [
              {"type": "line", "id": "line:IDFM:C01377", "disruptionIds": ["terminee"]},
              {"type": "stop_point", "id": "stop_point:IDFM:4", "name": "Villejuif",
               "disruptionIds": ["quai"]}
            ]}
          ]
        }
        """;

    private static final String LINE_ONLY = """
        {
          "disruptions": [
            {"id": "ligne-seule", "cause": "PERTURBATION", "severity": "PERTURBEE",
             "title": "Métro 9 : Incident", "shortMessage": "Trafic perturbé",
             "applicationPeriods": [{"begin": "20200101T000000", "end": "20991231T235959"}]}
          ],
          "lines": [
            {"id": "line:IDFM:C01379", "shortName": "9", "mode": "Metro", "impactedObjects": [
              {"type": "line", "id": "line:IDFM:C01379", "disruptionIds": ["ligne-seule"]}
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
        // La 7 est là pour son quai perturbé (une perturbation d'arrêt vaut pour sa ligne), mais
        // ses travaux de 2020 ne doivent pas compter : un seul item pour elle, deux pour la 9.
        mockMvc.perform(get("/disruptions"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.lines", hasSize(2)))
            .andExpect(jsonPath("$.lines[0].lineId").value("7"))
            .andExpect(jsonPath("$.lines[0].items", hasSize(1)))
            .andExpect(jsonPath("$.lines[1].lineId").value("9"))
            .andExpect(jsonPath("$.lines[1].items", hasSize(2)));
    }

    @Test
    void resolvesADisruptedPlatformToItsParentStation() throws Exception {
        // Le flux désigne le quai « stop_point:IDFM:4 » (P4 dans la fixture) ; la carte attend
        // la station parente PT4. C'est le serveur qui fait la résolution, seul à connaître les
        // quais de chaque station.
        mockMvc.perform(get("/disruptions"))
            .andExpect(jsonPath("$.stations", hasSize(1)))
            .andExpect(jsonPath("$.stations[0].stationId").value("PT4"))
            .andExpect(jsonPath("$.stations[0].severity").value("PERTURBEE"));
    }

    @Test
    void leavesStationsOutWhenOnlyTheWholeLineIsDisrupted() throws Exception {
        // Perturbation de ligne seule : aucun quai visé, donc aucun anneau sur la carte.
        poller.pollOnce(url -> new ByteArrayInputStream(LINE_ONLY.getBytes(StandardCharsets.UTF_8)),
            Instant.now());

        mockMvc.perform(get("/disruptions"))
            .andExpect(jsonPath("$.lines", hasSize(1)))
            .andExpect(jsonPath("$.stations", hasSize(0)));
    }

    @Test
    void reportsTheWorstSeverityOfTheLine() throws Exception {
        // Ciblé par lineId et non par index : la 9 cumule PERTURBEE et BLOQUANTE, et c'est la
        // pire qui doit remonter, quel que soit le rang de la ligne dans la réponse.
        mockMvc.perform(get("/disruptions"))
            .andExpect(jsonPath("$.lines[?(@.lineId == '9')].severity", hasItem("BLOQUANTE")))
            .andExpect(jsonPath("$.lines[?(@.lineId == '9')].items[0].title",
                hasItem(containsString("Trafic interrompu"))));
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
