package com.mapidf.rt;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import tools.jackson.databind.ObjectMapper;
import com.mapidf.configurations.properties.PrimProperties;
import com.mapidf.network.LineRegistry;
import com.mapidf.network.NetworkSnapshot;
import com.mapidf.network.TrackedLine;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class RealtimePollerResilienceTest {

    private static final String LINE_NINE = "STIF:Line::C01379:";

    private static PrimProperties prim() {
        return new PrimProperties("", "apikey", "", "http://realtime", Duration.ofSeconds(10));
    }

    private static LineRegistry registryTracking(String... siriLineRefs) {
        LineRegistry registry = new LineRegistry();
        List<TrackedLine> lines = List.of(siriLineRefs).stream()
            .map(ref -> new TrackedLine(ref, ref, ref, ref, "#000000", "METRO", List.of()))
            .toList();
        registry.publish(NetworkSnapshot.of(lines, List.of()));
        return registry;
    }

    @Test
    void keepsLastSnapshotOnFetchFailure() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        RealtimePoller poller = new RealtimePoller(prim(), new ObjectMapper(), registryTracking(LINE_NINE));
        poller.attachMetrics(meterRegistry);

        byte[] siri = RtFixtures.siriLineNineSample();
        poller.pollOnce(url -> RtFixtures.stream(siri), Instant.ofEpochSecond(100));
        assertThat(poller.current().forLine(LINE_NINE))
            .extracting(RtSnapshot.LiveJourney::journeyRef).contains("J1");

        poller.pollOnce(url -> {
            throw new RuntimeException("IDFM down");
        }, Instant.ofEpochSecond(200));

        assertThat(poller.current().forLine(LINE_NINE))
            .extracting(RtSnapshot.LiveJourney::journeyRef).contains("J1");
        assertThat(meterRegistry.counter("mapidf.rt.poll.failures").count()).isEqualTo(1.0);
    }

    @Test
    void decodesAGzippedBody() throws Exception {
        // Mesuré : PRIM répond Content-Encoding: gzip, 3,96 Mo au lieu de 45,6 Mo.
        byte[] gzipped = RtFixtures.gzip(RtFixtures.siriMultiLineSample());
        RtSnapshot snapshot = RealtimePoller.parse(
            new ObjectMapper(), new java.util.zip.GZIPInputStream(RtFixtures.stream(gzipped)),
            Instant.parse("2026-07-22T14:00:00Z"), Set.of(LINE_NINE));

        assertThat(snapshot.forLine(LINE_NINE)).hasSize(1);
    }

    // La démonstration ci-dessus (decodesAGzippedBody) gzippe puis dégzippe elle-même avant
    // d'appeler parse() : elle prouve seulement que parse() lit un InputStream quelconque, pas
    // que le poller décode réellement le Content-Encoding: gzip renvoyé par PRIM. On vérifie
    // donc ici, au niveau HTTP réel (serveur JDK embarqué), que fetch() décode bien un corps
    // gzippé quand le serveur annonce Content-Encoding: gzip.
    @Test
    void fetchDecodesAGzipContentEncodedHttpResponse() throws Exception {
        byte[] raw = RtFixtures.siriMultiLineSample();
        byte[] gzipped = RtFixtures.gzip(raw);

        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/rt", exchange -> {
            exchange.getResponseHeaders().add("Content-Encoding", "gzip");
            exchange.sendResponseHeaders(200, gzipped.length);
            try (var os = exchange.getResponseBody()) {
                os.write(gzipped);
            }
        });
        server.start();
        try {
            RealtimePoller poller = new RealtimePoller(prim(), new ObjectMapper(), registryTracking(LINE_NINE));
            String url = "http://localhost:" + server.getAddress().getPort() + "/rt";

            byte[] decoded;
            try (InputStream body = poller.fetch(url)) {
                decoded = body.readAllBytes();
            }

            assertThat(decoded).isEqualTo(raw);
        } finally {
            server.stop(0);
        }
    }
}
