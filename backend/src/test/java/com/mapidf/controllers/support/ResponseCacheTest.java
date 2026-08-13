package com.mapidf.controllers.support;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ResponseCacheTest {

    /** Java n'a pas d'horloge mutable : sans elle, éprouver la frontière de seconde imposerait
     *  de dormir, donc un test lent et instable. */
    private static final class TestClock extends Clock {
        private Instant now;

        TestClock(Instant now) {
            this.now = now;
        }

        void set(Instant next) {
            this.now = next;
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    private static final Instant T0 = Instant.parse("2026-08-12T08:00:00.400Z");

    private TestClock clock;
    private MeterRegistry meters;
    private ResponseCache<String, String> cache;
    private AtomicInteger calls;

    @BeforeEach
    void setUp() {
        clock = new TestClock(T0);
        meters = new SimpleMeterRegistry();
        cache = new ResponseCache<>(clock, "test", meters);
        calls = new AtomicInteger();
    }

    private String compute(Instant now) {
        calls.incrementAndGet();
        return "valeur@" + now;
    }

    @Test
    void reusesTheEntryForTheSameSourcesWithinTheSameSecond() {
        Object source = new Object();

        String first = cache.get(List.of(source), this::compute);
        String second = cache.get(List.of(source), this::compute);

        assertThat(calls).hasValue(1);
        assertThat(second).isEqualTo(first);
    }

    @Test
    void recomputesWhenASourceIsAnEqualButFreshInstance() {
        // Le cœur du chantier : les instantanés sont des records, donc porteurs d'un equals
        // structurel. C'est l'IDENTITÉ qu'on veut, parce qu'un poll publie une instance neuve.
        // Ce test rougit si quelqu'un « corrige » == en equals.
        record Snapshot(String value) { }

        cache.get(List.of(new Snapshot("x")), this::compute);
        cache.get(List.of(new Snapshot("x")), this::compute);

        assertThat(calls).hasValue(2);
    }

    @Test
    void recomputesOnTheNextSecondEvenWithUnchangedSources() {
        Object source = new Object();
        cache.get(List.of(source), this::compute);

        clock.set(Instant.parse("2026-08-12T08:00:01.000Z"));
        cache.get(List.of(source), this::compute);

        assertThat(calls).hasValue(2);
    }

    @Test
    void keepsDistinctKeysIndependent() {
        Object source = new Object();

        cache.get("A", List.of(source), this::compute);
        cache.get("B", List.of(source), this::compute);
        cache.get("A", List.of(source), this::compute);

        assertThat(calls).hasValue(2);
    }

    @Test
    void handsTheComputationTheInstantThatKeyedTheEntry() {
        // Le calcul reçoit l'instant PLEIN (08:00:00.400), pas la seconde tronquée : la position
        // servie est la plus fraîche possible. Mais les deux viennent du MÊME appel à l'horloge,
        // sans quoi un calcul fait à 08:00:01.001 pourrait s'enregistrer sous la seconde 08:00:00.
        Object source = new Object();

        String value = cache.get(List.of(source), this::compute);

        assertThat(value).isEqualTo("valeur@" + T0);

        clock.set(Instant.parse("2026-08-12T08:00:00.999Z"));
        assertThat(cache.get(List.of(source), this::compute)).isEqualTo(value);
        assertThat(calls).hasValue(1);
    }

    @Test
    void countsHitsAndMisses() {
        Object source = new Object();

        cache.get(List.of(source), this::compute);
        cache.get(List.of(source), this::compute);
        cache.get(List.of(source), this::compute);

        assertThat(meters.counter("mapidf.cache.misses", "cache", "test").count()).isEqualTo(1.0);
        assertThat(meters.counter("mapidf.cache.hits", "cache", "test").count()).isEqualTo(2.0);
    }
}
