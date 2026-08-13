package com.mapidf.configurations;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ClockConfiguration {

    /** Le projet n'avait pas d'horloge injectable : les contrôleurs appelaient Instant.now(). */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
