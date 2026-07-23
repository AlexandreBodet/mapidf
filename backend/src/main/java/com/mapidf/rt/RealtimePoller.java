package com.mapidf.rt;

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

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Component
public class RealtimePoller {

    @FunctionalInterface
    public interface Fetcher {
        byte[] get(String url) throws Exception;
    }

    // Fenêtre de service métro (Europe/Paris), enjambe minuit : inutile de poller la nuit.
    private static final ZoneId PARIS = ZoneId.of("Europe/Paris");
    private static final LocalTime SERVICE_START = LocalTime.of(5, 30);
    private static final LocalTime SERVICE_END = LocalTime.of(1, 30);

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
        if (prim.realtimeBaseUrl() == null || prim.realtimeBaseUrl().isBlank()) {
            return;
        }
        if (!inServiceHours(LocalTime.now(PARIS))) {
            return;
        }
        pollOnce(this::fetch, Instant.now());
    }

    // Vrai pendant la fenêtre de service, qui enjambe minuit (05h30 → 01h30).
    static boolean inServiceHours(LocalTime now) {
        return !now.isBefore(SERVICE_START) || now.isBefore(SERVICE_END);
    }

    void pollOnce(Fetcher fetcher, Instant asOf) {
        try {
            snapshot.set(parse(objectMapper, fetcher.get(buildUrl()), asOf));
        } catch (Exception e) {
            if (pollFailures != null) {
                pollFailures.increment();
            }
            log.warn("[RT] Échec du poll, snapshot conservé: {}", e.getMessage());
        }
    }

    // estimated-timetable : sans LineRef = tout le réseau (prêt multi-lignes) ;
    // avec LineRef = filtré sur la ligne suivie (réponse légère au MVP mono-ligne).
    private String buildUrl() {
        String base = prim.realtimeBaseUrl();
        if (line.siriLineRef() == null || line.siriLineRef().isBlank()) {
            return base;
        }
        return base + "?LineRef=" + URLEncoder.encode(line.siriLineRef(), StandardCharsets.UTF_8);
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

    // Parse le flux SIRI-ET (global ou filtré) en indexant les courses par LineRef.
    static RtSnapshot parse(ObjectMapper mapper, byte[] json, Instant asOf) {
        if (json == null || json.length == 0) {
            return new RtSnapshot(asOf, Map.of());
        }
        JsonNode deliveries = mapper.readTree(json)
            .path("Siri").path("ServiceDelivery").path("EstimatedTimetableDelivery");

        Map<String, List<RtSnapshot.LiveJourney>> byLine = new HashMap<>();
        for (JsonNode delivery : deliveries) {
            for (JsonNode frame : delivery.path("EstimatedJourneyVersionFrame")) {
                for (JsonNode journey : frame.path("EstimatedVehicleJourney")) {
                    RtSnapshot.LiveJourney live = toJourney(journey);
                    if (live != null) {
                        byLine.computeIfAbsent(live.lineRef(), key -> new ArrayList<>()).add(live);
                    }
                }
            }
        }
        return new RtSnapshot(asOf, byLine);
    }

    private static RtSnapshot.LiveJourney toJourney(JsonNode journey) {
        JsonNode calls = journey.path("EstimatedCalls").path("EstimatedCall");
        JsonNode call = calls.isArray() ? calls.path(0) : calls;
        String stopRef = call.path("StopPointRef").path("value").asString(null);
        String eta = call.path("ExpectedDepartureTime").asString(call.path("ExpectedArrivalTime").asString(null));
        if (stopRef == null || eta == null) {
            return null;
        }
        String lineRef = journey.path("LineRef").path("value").asString("");
        String journeyRef = journey.path("DatedVehicleJourneyRef").path("value").asString(stopRef);
        String directionRef = journey.path("DirectionRef").path("value").asString("");
        String destination = firstValue(journey.path("DestinationName"));
        String departureStatus = call.path("DepartureStatus").asString("");
        return new RtSnapshot.LiveJourney(
            lineRef, journeyRef, directionRef, destination, stopRef, Instant.parse(eta), departureStatus);
    }

    private static String firstValue(JsonNode node) {
        if (node.isArray() && !node.isEmpty()) {
            return node.get(0).path("value").asString("");
        }
        return node.path("value").asString("");
    }
}
