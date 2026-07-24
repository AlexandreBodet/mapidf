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
            log.info("[RT] Poll réussi");
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
                    RtSnapshot.LiveJourney live = toJourney(journey, asOf);
                    if (live != null) {
                        byLine.computeIfAbsent(live.lineRef(), key -> new ArrayList<>()).add(live);
                    }
                }
            }
        }
        return new RtSnapshot(asOf, byLine);
    }

    private static RtSnapshot.LiveJourney toJourney(JsonNode journey, Instant asOf) {
        JsonNode call = pickNextCall(journey.path("EstimatedCalls").path("EstimatedCall"), asOf);
        if (call == null) {
            return null;
        }
        String stopRef = call.path("StopPointRef").path("value").asString(null);
        Instant eta = callTime(call);
        if (stopRef == null || eta == null) {
            return null;
        }
        String lineRef = journey.path("LineRef").path("value").asString("");
        String journeyRef = journey.path("DatedVehicleJourneyRef").path("value").asString(stopRef);
        String directionRef = journey.path("DirectionRef").path("value").asString("");
        String destination = firstValue(journey.path("DestinationName"));
        String departureStatus = call.path("DepartureStatus").asString("");
        return new RtSnapshot.LiveJourney(
            lineRef, journeyRef, directionRef, destination, stopRef, eta, departureStatus);
    }

    // La liste EstimatedCall du flux PRIM N'EST PAS triée (ni par heure ni par ordre d'arrêt,
    // le champ Order est absent). On choisit donc l'arrêt IMMINENT = le plus tôt encore à venir
    // (>= asOf) ; s'il n'y a plus d'arrêt futur (course en fin de parcours), le plus tardif connu.
    private static JsonNode pickNextCall(JsonNode callsNode, Instant asOf) {
        JsonNode best = null;
        Instant bestTime = null;
        boolean bestFuture = false;
        for (JsonNode call : callList(callsNode)) {
            Instant t = callTime(call);
            if (t == null) {
                continue;
            }
            boolean future = !t.isBefore(asOf);
            if (best == null
                || (future && (!bestFuture || t.isBefore(bestTime)))
                || (!future && !bestFuture && t.isAfter(bestTime))) {
                best = call;
                bestTime = t;
                bestFuture = future;
            }
        }
        return best;
    }

    private static List<JsonNode> callList(JsonNode callsNode) {
        if (callsNode.isArray()) {
            List<JsonNode> list = new ArrayList<>();
            callsNode.forEach(list::add);
            return list;
        }
        if (callsNode.isMissingNode() || callsNode.isNull()) {
            return List.of();
        }
        return List.of(callsNode);
    }

    // Heure de passage au prochain arrêt : arrivée en priorité (moment où le train l'atteint),
    // départ à défaut.
    private static Instant callTime(JsonNode call) {
        String iso = call.path("ExpectedArrivalTime").asString(
            call.path("ExpectedDepartureTime").asString(null));
        return iso == null ? null : Instant.parse(iso);
    }

    private static String firstValue(JsonNode node) {
        if (node.isArray() && !node.isEmpty()) {
            return node.get(0).path("value").asString("");
        }
        return node.path("value").asString("");
    }
}
