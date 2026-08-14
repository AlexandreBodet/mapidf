package com.mapidf.controllers.support;

import java.time.Instant;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class RateLimiterTest {

    /** Milieu de seconde ET début de minute : la troncature à la minute doit se voir. */
    private static final Instant T0 = Instant.parse("2026-08-13T08:00:00.400Z");

    private static final int BUDGET = 3;

    private TestClock clock;
    private MeterRegistry meters;
    private RateLimiter limiter;

    @BeforeEach
    void setUp() {
        clock = new TestClock(T0);
        meters = new SimpleMeterRegistry();
        limiter = new RateLimiter(clock, BUDGET, meters);
    }

    private double rejections() {
        return meters.counter("mapidf.ratelimit.rejected").count();
    }

    @Test
    void laissePasserJusquAuBudget() {
        for (int i = 1; i <= BUDGET; i++) {
            assertThat(limiter.check("1.2.3.4").allowed())
                .as("requête %d sur un budget de %d", i, BUDGET)
                .isTrue();
        }
    }

    @Test
    void refuseLaRequeteQuiDepasseLeBudget() {
        for (int i = 0; i < BUDGET; i++) {
            limiter.check("1.2.3.4");
        }

        assertThat(limiter.check("1.2.3.4").allowed()).isFalse();
    }

    @Test
    void laFenetreSuivanteRouvreLeBudget() {
        for (int i = 0; i <= BUDGET; i++) {
            limiter.check("1.2.3.4");
        }
        assertThat(limiter.check("1.2.3.4").allowed()).isFalse();

        clock.set(T0.plusSeconds(60));

        assertThat(limiter.check("1.2.3.4").allowed()).isTrue();
    }

    @Test
    void laFenetreEstTronqueeALaMinuteEtNonGlissante() {
        clock.set(Instant.parse("2026-08-13T08:00:59.900Z"));
        for (int i = 0; i < BUDGET; i++) {
            limiter.check("1.2.3.4");
        }
        assertThat(limiter.check("1.2.3.4").allowed()).isFalse();

        // 200 ms plus tard seulement, mais de l'autre côté de la minute : budget neuf. C'est le
        // prix assumé de la fenêtre fixe, et il doit être constaté, pas subi.
        clock.set(Instant.parse("2026-08-13T08:01:00.100Z"));

        assertThat(limiter.check("1.2.3.4").allowed()).isTrue();
    }

    @Test
    void compteChaqueCleSeparement() {
        for (int i = 0; i <= BUDGET; i++) {
            limiter.check("1.2.3.4");
        }
        assertThat(limiter.check("1.2.3.4").allowed()).isFalse();

        assertThat(limiter.check("5.6.7.8").allowed()).isTrue();
    }

    @Test
    void rendLesSecondesRestantJusquALaFinDeLaFenetre() {
        assertThat(limiter.check("1.2.3.4").retryAfterSeconds()).isEqualTo(60);

        clock.set(Instant.parse("2026-08-13T08:00:30.400Z"));
        assertThat(limiter.check("1.2.3.4").retryAfterSeconds()).isEqualTo(30);

        clock.set(Instant.parse("2026-08-13T08:00:59.900Z"));
        assertThat(limiter.check("1.2.3.4").retryAfterSeconds()).isEqualTo(1);
    }

    @Test
    void evinceLesEntreesDUneFenetrePassee() {
        limiter.check("1.2.3.4");
        limiter.check("5.6.7.8");
        assertThat(limiter.trackedKeys()).isEqualTo(2);

        clock.set(T0.plusSeconds(60));
        limiter.check("9.10.11.12");

        // La map est indexée par une clé que l'appelant choisit : sans balayage elle croît avec
        // le nombre d'IP vues et ne décroît jamais.
        assertThat(limiter.trackedKeys()).isEqualTo(1);
    }

    @Test
    void compteLesRejets() {
        assertThat(rejections()).isZero();

        for (int i = 0; i <= BUDGET; i++) {
            limiter.check("1.2.3.4");
        }

        assertThat(rejections()).isEqualTo(1);

        limiter.check("1.2.3.4");

        assertThat(rejections()).isEqualTo(2);
    }
}
