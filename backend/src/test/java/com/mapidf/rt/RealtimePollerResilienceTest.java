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
        return new PrimProperties("", "apikey", "", "http://realtime", Duration.ofSeconds(10),
            "", Duration.ofMinutes(5));
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
    void publishesAJourneyGaugePerTrackedLineIncludingLinesAbsentFromTheFeed() {
        // Garde-fou du risque n°1 de la spec : si IDFM introduit une ligne au code atypique, la
        // dérivation de son LineRef échoue et elle tombe à zéro train. Agrégée sur le réseau, la
        // métrique resterait de l'ordre de 700 et ne dirait rien ; taggée par ligne, le zéro se
        // voit. La jauge est donc publiée pour TOUTES les lignes du registry, y compris celles
        // absentes du flux — ici la 7, que la fixture ne contient pas.
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        LineRegistry lines = new LineRegistry();
        lines.publish(NetworkSnapshot.of(List.of(
            new TrackedLine("9", "IDFM:C01379", LINE_NINE, "9", "#D2D200", "METRO", List.of()),
            new TrackedLine("7", "IDFM:C01377", "STIF:Line::C01377:", "7", "#FF82B4", "METRO",
                List.of())), List.of()));
        RealtimePoller poller = new RealtimePoller(prim(), new ObjectMapper(), lines);
        poller.attachMetrics(meterRegistry);

        poller.pollOnce(url -> RtFixtures.stream(RtFixtures.siriMultiLineSample()),
            Instant.ofEpochSecond(100));

        assertThat(meterRegistry.get("mapidf.rt.journeys").tag("line", "9").gauge().value())
            .isEqualTo(1.0);
        assertThat(meterRegistry.get("mapidf.rt.journeys").tag("line", "7").gauge().value())
            .isEqualTo(0.0);
    }

    @Test
    void parseReadsAnAlreadyDecompressedStream() {
        // Ce test ne prouve RIEN sur le décodage HTTP : il gzippe puis dégzippe lui-même avant
        // d'appeler parse(), qui ignore totalement l'encodage et ne lit qu'un InputStream. Il vaut
        // pour ce qu'il dit : un flux multi-lignes déjà décompressé est parsé et filtré sur les
        // LineRef suivis. Le décodage réel du Content-Encoding est couvert par le test suivant.
        byte[] gzipped;
        try {
            gzipped = RtFixtures.gzip(RtFixtures.siriMultiLineSample());
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
        RtSnapshot snapshot;
        try (var in = new java.util.zip.GZIPInputStream(RtFixtures.stream(gzipped))) {
            snapshot = RealtimePoller.parse(new ObjectMapper(), in,
                Instant.parse("2026-07-22T14:00:00Z"), Set.of(LINE_NINE));
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }

        assertThat(snapshot.forLine(LINE_NINE)).hasSize(1);
    }

    // Ici en revanche on vérifie, au niveau HTTP réel (serveur JDK embarqué), que fetch() décode
    // bien un corps gzippé quand le serveur annonce Content-Encoding: gzip. Mesuré sur PRIM :
    // 3,96 Mo au lieu de 45,6 Mo (×11,5).
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
