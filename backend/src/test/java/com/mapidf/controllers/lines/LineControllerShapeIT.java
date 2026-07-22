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
class LineControllerShapeIT {

    @Autowired WebApplicationContext wac;
    @Autowired GtfsStaticLoader loader;
    MockMvc mockMvc;

    @BeforeEach
    void setup() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).build();
        try (var in = getClass().getResourceAsStream("/gtfs-mini.zip")) {
            loader.loadFromZip(in, "TEST9");
        }
    }

    @Test
    void returnsShapeAndStops() throws Exception {
        mockMvc.perform(get("/lines/TEST9/shape"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.lineId").value("TEST9"))
            .andExpect(jsonPath("$.shape.length()").value(3))
            .andExpect(jsonPath("$.stops.length()").value(3));
    }
}
