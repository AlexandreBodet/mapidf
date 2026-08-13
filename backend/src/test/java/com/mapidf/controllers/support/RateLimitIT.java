package com.mapidf.controllers.support;

import com.mapidf.MapIdfTest;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Budget ramené à 3 par @TestPropertySource : la propriété diffère de celle des autres IT, donc
 * Spring construit un contexte distinct et ce fichier n'a aucun effet sur eux.
 */
@MapIdfTest
@TestPropertySource(properties = "app.ratelimit.requests-per-minute=3")
class RateLimitIT {

    private static final int BUDGET = 3;

    @Autowired WebApplicationContext wac;
    @Autowired MeterRegistry meters;
    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).build();
    }

    /**
     * Le RateLimiter est un champ du bean interceptor, donc partagé par toutes les méthodes de
     * ce contexte : chaque test prend une IP distincte pour ne pas hériter du compteur du
     * précédent. Adresses de RFC 5737 (TEST-NET-3), jamais routées.
     */
    private ResultActions call(String ip) throws Exception {
        return mockMvc.perform(get("/network").with(request -> {
            request.setRemoteAddr(ip);
            return request;
        }));
    }

    @Test
    void refuseAuDelaDuBudgetAvecUn429Complet() throws Exception {
        for (int i = 0; i < BUDGET; i++) {
            call("203.0.113.7").andExpect(status().isOk());
        }

        call("203.0.113.7")
            .andExpect(status().isTooManyRequests())
            .andExpect(header().exists("Retry-After"))
            .andExpect(jsonPath("$.status").value(429))
            .andExpect(jsonPath("$.errorCode").value("TOO_MANY_REQUESTS"))
            .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void leRetryAfterEstUnNombreDeSecondesExploitable() throws Exception {
        for (int i = 0; i < BUDGET; i++) {
            call("203.0.113.8");
        }

        String retryAfter = call("203.0.113.8")
            .andExpect(status().isTooManyRequests())
            .andReturn().getResponse().getHeader("Retry-After");

        assertThat(retryAfter).isNotNull();
        assertThat(Long.parseLong(retryAfter)).isBetween(1L, 60L);
    }

    @Test
    void compteLeRejet() throws Exception {
        double avant = meters.counter("mapidf.ratelimit.rejected").count();

        for (int i = 0; i <= BUDGET; i++) {
            call("203.0.113.9");
        }

        assertThat(meters.counter("mapidf.ratelimit.rejected").count()).isEqualTo(avant + 1);
    }

    @Test
    void neRefuseJamaisLaLoopback() throws Exception {
        // C'est aussi ce qui protège les IT existants : ils passent tous par MockMvc, donc par
        // remoteAddr = 127.0.0.1, dans un contexte Spring mis en cache entre classes de test.
        for (int i = 0; i < BUDGET * 4; i++) {
            call("127.0.0.1").andExpect(status().isOk());
        }
    }
}
