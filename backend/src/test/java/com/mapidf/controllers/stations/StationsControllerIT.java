package com.mapidf.controllers.stations;

import com.mapidf.MapIdfTest;
import com.mapidf.gtfs.GtfsStaticLoader;
import com.mapidf.gtfs.GtfsStaticService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@MapIdfTest
class StationsControllerIT {

    @Autowired WebApplicationContext wac;
    @Autowired GtfsStaticLoader loader;
    @Autowired GtfsStaticService staticService;
    MockMvc mockMvc;

    @BeforeEach
    void setup() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).build();
        try (var in = getClass().getResourceAsStream("/gtfs-branch.zip")) {
            loader.load(in);
        }
        staticService.publishFromDatabase();
    }

    @Test
    void returnsTheStationEnvelopeForACorrespondence() throws Exception {
        mockMvc.perform(get("/stations/STC/departures"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.stationName").value("Correspondance"))
            .andExpect(jsonPath("$.lines").isArray());
    }

    @Test
    void returnsNotFoundForAnUnknownStation() throws Exception {
        mockMvc.perform(get("/stations/NOPE/departures"))
            .andExpect(status().isNotFound());
    }
}
