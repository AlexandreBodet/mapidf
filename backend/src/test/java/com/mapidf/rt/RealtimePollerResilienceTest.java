package com.mapidf.rt;

import java.time.Duration;
import java.time.Instant;

import tools.jackson.databind.ObjectMapper;
import com.mapidf.configurations.properties.PrimProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class RealtimePollerResilienceTest {

    private static PrimProperties prim() {
        return new PrimProperties("", "apikey", "", "http://realtime", Duration.ofSeconds(10));
    }

    @Test
    void keepsLastSnapshotOnFetchFailure() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RealtimePoller poller = new RealtimePoller(prim(), new ObjectMapper());
        poller.attachMetrics(registry);

        byte[] siri = RtFixtures.siriLineNineSample();
        poller.pollOnce(url -> siri, Instant.ofEpochSecond(100));
        assertThat(poller.current().forLine("STIF:Line::C01379:"))
            .extracting(RtSnapshot.LiveJourney::journeyRef).contains("J1");

        poller.pollOnce(url -> {
            throw new RuntimeException("IDFM down");
        }, Instant.ofEpochSecond(200));

        assertThat(poller.current().forLine("STIF:Line::C01379:"))
            .extracting(RtSnapshot.LiveJourney::journeyRef).contains("J1");
        assertThat(registry.counter("mapidf.rt.poll.failures").count()).isEqualTo(1.0);
    }
}
