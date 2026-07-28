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
    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();
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

    // fixedDelay : le prochain poll ne démarre qu'après la fin du précédent → pas de
    // chevauchement ni de rafale de connexions vers PRIM si un appel traîne.
    @Scheduled(fixedDelayString = "${app.prim.poll-interval}")
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
            .timeout(Duration.ofSeconds(10))  // bien < l'intervalle de poll (60 s)
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
                    try {
                        RtSnapshot.LiveJourney live = toJourney(journey);
                        if (live != null) {
                            byLine.computeIfAbsent(live.lineRef(), key -> new ArrayList<>()).add(live);
                        }
                    } catch (RuntimeException e) {
                        // Une course pourrie (horodatage illisible, structure inattendue) ne doit pas
                        // faire perdre tout le snapshot — surtout en réseau complet (multi-ligne).
                        log.warn("[RT] Course ignorée (parse impossible): {}", e.getMessage());
                    }
                }
            }
        }
        return new RtSnapshot(asOf, byLine);
    }

    // Construit la course = son identité + TOUS ses appels (arrêts estimés). La liste du flux
    // n'est ni triée ni bornée au prochain arrêt : le tri et le choix de l'arrêt imminent se font
    // dans PositionEngine (qui connaît l'instant de calcul). On ignore les appels sans arrêt/heure.
    private static RtSnapshot.LiveJourney toJourney(JsonNode journey) {
        List<RtSnapshot.LiveJourney.Call> calls = new ArrayList<>();
        for (JsonNode call : callList(journey.path("EstimatedCalls").path("EstimatedCall"))) {
            String stopRef = call.path("StopPointRef").path("value").asString(null);
            Instant time = callTime(call);
            if (stopRef == null || time == null) {
                continue;
            }
            calls.add(new RtSnapshot.LiveJourney.Call(
                stopRef, time, call.path("DepartureStatus").asString("")));
        }
        if (calls.isEmpty()) {
            return null;
        }
        String lineRef = journey.path("LineRef").path("value").asString("");
        String directionRef = journey.path("DirectionRef").path("value").asString("");
        String destination = firstValue(journey.path("DestinationName"));
        // DatedVehicleJourneyRef est souvent absent : on replie sur une identité composite
        // (et non sur le seul stopRef, qui collisionnerait entre deux courses de sens opposés
        // partageant leur premier arrêt du flux non trié) → pas de fusion de trains côté front.
        String journeyRef = journey.path("DatedVehicleJourneyRef").path("value").asString(null);
        if (journeyRef == null) {
            journeyRef = lineRef + "|" + directionRef + "|" + destination + "|" + calls.getFirst().time();
        }
        return new RtSnapshot.LiveJourney(lineRef, journeyRef, directionRef, destination, calls);
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
