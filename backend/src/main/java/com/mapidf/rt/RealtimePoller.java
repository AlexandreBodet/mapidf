package com.mapidf.rt;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.mapidf.configurations.properties.LineProperties;
import com.mapidf.configurations.properties.PrimProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@Component
public class RealtimePoller {

    @FunctionalInterface
    public interface Fetcher {
        byte[] get(String url) throws Exception;
    }

    private final PrimProperties prim;
    private final LineProperties line;
    private final ObjectMapper objectMapper;
    private final AtomicReference<RtSnapshot> snapshot = new AtomicReference<>(RtSnapshot.empty());
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private Counter pollFailures;

    public RealtimePoller(PrimProperties prim, LineProperties line, ObjectMapper objectMapper) {
        this.prim = prim;
        this.line = line;
        this.objectMapper = objectMapper;
    }

    @Autowired
    public void attachMetrics(MeterRegistry registry) {
        this.pollFailures = registry.counter("mapidf.rt.poll.failures");
        registry.gauge("mapidf.rt.snapshot.age.seconds", snapshot,
            ref -> ref.get().asOf().equals(Instant.EPOCH)
                ? 0.0
                : Duration.between(ref.get().asOf(), Instant.now()).getSeconds());
    }

    public RtSnapshot current() {
        return snapshot.get();
    }

    @Scheduled(fixedRateString = "${app.prim.poll-interval}")
    public void poll() {
        if (prim.realtimeBaseUrl() == null || prim.realtimeBaseUrl().isBlank()
            || line.siriLineRef() == null || line.siriLineRef().isBlank()) {
            return;
        }
        pollOnce(this::fetch, Instant.now());
    }

    void pollOnce(Fetcher fetcher, Instant asOf) {
        try {
            String url = prim.realtimeBaseUrl()
                + "?LineRef=" + URLEncoder.encode(line.siriLineRef(), StandardCharsets.UTF_8);
            snapshot.set(parse(objectMapper, fetcher.get(url), asOf));
        } catch (Exception e) {
            if (pollFailures != null) {
                pollFailures.increment();
            }
            log.warn("[RT] Échec du poll, snapshot conservé: {}", e.getMessage());
        }
    }

    private byte[] fetch(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
            .header(prim.authHeader(), prim.apiKey())
            .GET()
            .build();
        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        // On DOIT vérifier le code HTTP : sur 429 (quota) ou 5xx, PRIM renvoie un corps
        // JSON d'erreur qui se parserait en 0 course et écraserait le dernier bon snapshot.
        // En levant ici, pollOnce conserve le snapshot précédent (dégradation gracieuse).
        if (response.statusCode() / 100 != 2) {
            throw new IOException("réponse HTTP " + response.statusCode() + " de PRIM");
        }
        return response.body();
    }

    static RtSnapshot parse(ObjectMapper mapper, byte[] json, Instant asOf) throws Exception {
        if (json == null || json.length == 0) {
            return new RtSnapshot(asOf, List.of());
        }
        JsonNode journeysNode = mapper.readTree(json)
            .path("Siri").path("ServiceDelivery")
            .path("EstimatedTimetableDelivery").path(0)
            .path("EstimatedJourneyVersionFrame").path(0)
            .path("EstimatedVehicleJourney");

        List<RtSnapshot.LiveJourney> journeys = new ArrayList<>();
        for (JsonNode journey : journeysNode) {
            JsonNode calls = journey.path("EstimatedCalls").path("EstimatedCall");
            JsonNode call = calls.isArray() ? calls.path(0) : calls;
            String stopRef = call.path("StopPointRef").path("value").asText(null);
            String eta = call.path("ExpectedDepartureTime")
                .asText(call.path("ExpectedArrivalTime").asText(null));
            if (stopRef == null || eta == null) {
                continue;
            }
            String journeyRef = journey.path("DatedVehicleJourneyRef").path("value").asText(stopRef);
            String directionRef = journey.path("DirectionRef").path("value").asText("");
            String destination = firstValue(journey.path("DestinationName"));
            String departureStatus = call.path("DepartureStatus").asText("");
            journeys.add(new RtSnapshot.LiveJourney(
                journeyRef, directionRef, destination, stopRef, Instant.parse(eta), departureStatus));
        }
        return new RtSnapshot(asOf, List.copyOf(journeys));
    }

    private static String firstValue(JsonNode node) {
        if (node.isArray() && !node.isEmpty()) {
            return node.get(0).path("value").asText("");
        }
        return node.path("value").asText("");
    }
}
