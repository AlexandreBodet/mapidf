package com.mapidf.controllers.lines;

import com.mapidf.MapIdfTest;
import com.mapidf.gtfs.GtfsStaticLoader;
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
class LineControllerDeparturesIT {

    @Autowired WebApplicationContext wac;
    @Autowired GtfsStaticLoader loader;
    MockMvc mockMvc;

    @BeforeEach
    void setup() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).build();
        try (var in = getClass().getResourceAsStream("/gtfs-parent.zip")) {
            loader.loadFromZip(in, "RP");
        }
    }

    @Test
    void unknownStationReturns404() throws Exception {
        mockMvc.perform(get("/lines/9/stations/INCONNU/departures"))
            .andExpect(status().isNotFound());
    }

    @Test
    void knownStationWithoutLiveDataReturnsEmptyDirections() throws Exception {
        // Pas de snapshot temps réel injecté → station connue (SAA) mais aucune direction.
        mockMvc.perform(get("/lines/9/stations/SAA/departures"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.stationName").value("Alpha"))
            .andExpect(jsonPath("$.directions.length()").value(0));
    }
}
