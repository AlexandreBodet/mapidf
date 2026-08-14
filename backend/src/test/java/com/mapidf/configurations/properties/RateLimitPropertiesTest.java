package com.mapidf.configurations.properties;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RateLimitPropertiesTest {

    @Test
    void accepteUnBudgetPositif() {
        assertThat(new RateLimitProperties(600).requestsPerMinute()).isEqualTo(600);
    }

    @Test
    void refuseUnBudgetNulOuNegatif() {
        // Un budget à 0 refuserait tout le trafic en silence : mieux vaut ne pas démarrer que
        // servir des 429 à tout le monde parce qu'une variable d'environnement est vide.
        assertThatThrownBy(() -> new RateLimitProperties(0))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("app.ratelimit.requests-per-minute");

        assertThatThrownBy(() -> new RateLimitProperties(-1))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
