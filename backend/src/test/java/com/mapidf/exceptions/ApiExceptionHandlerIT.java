package com.mapidf.exceptions;

import com.mapidf.MapIdfTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * L'attrape-tout {@code @ExceptionHandler(Exception.class)} classait en 500 toutes les exceptions
 * du framework, y compris celles qui décrivent une faute du client (QUA-14).
 */
@MapIdfTest
class ApiExceptionHandlerIT {

    @Autowired WebApplicationContext wac;
    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).build();
    }

    @Test
    void unCheminNonMappeRend404EtNonUneErreurInterne() throws Exception {
        mockMvc.perform(get("/nope"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.status").value(404))
            .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"))
            .andExpect(jsonPath("$.path").value("/nope"));
    }

    @Test
    void uneMethodeNonSupporteeRend405EtNonUneErreurInterne() throws Exception {
        mockMvc.perform(post("/vehicles"))
            .andExpect(status().isMethodNotAllowed())
            .andExpect(jsonPath("$.status").value(405))
            .andExpect(jsonPath("$.errorCode").value("BAD_REQUEST"));
    }

    @Test
    void uneStationInconnueGardeSonCodeMetier() throws Exception {
        // Garde-fou : le traitement des exceptions du framework ne doit pas court-circuiter les
        // ApiException du projet, qui ont leur propre handler et leur propre code.
        mockMvc.perform(get("/stations/inexistante/departures"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.errorCode").value("STATION_NOT_FOUND"));
    }
}
