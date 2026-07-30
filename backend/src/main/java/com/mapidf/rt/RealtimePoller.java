package com.mapidf.rt;

import com.mapidf.configurations.properties.PrimProperties;
import com.mapidf.network.LineRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPInputStream;

@Slf4j
@Component
public class RealtimePoller {

    @FunctionalInterface
    public interface Fetcher {
        InputStream get(String url) throws Exception;
    }

    // Mesuré le 2026-07-29 : 5,8 s pour 3,96 Mo. On garde une marge confortable tout en
    // restant nettement sous l'intervalle de poll (60 s), pour ne jamais chevaucher.
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(45);

    // Fenêtre de service métro (Europe/Paris), enjambe minuit : inutile de poller la nuit.
    private static final ZoneId PARIS = ZoneId.of("Europe/Paris");
    private static final LocalTime SERVICE_START = LocalTime.of(5, 30);
    private static final LocalTime SERVICE_END = LocalTime.of(1, 30);

    private final PrimProperties prim;
    private final ObjectMapper objectMapper;
    private final LineRegistry registry;
    private final AtomicReference<RtSnapshot> snapshot = new AtomicReference<>(RtSnapshot.empty());
    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();
    private Counter pollFailures;
    private MeterRegistry meters;
    // Une jauge par ligne suivie, exigée par la spec comme garde-fou de son risque n°1 : si IDFM
    // introduit une ligne au code atypique, la dérivation du LineRef échoue et la ligne tombe à
    // zéro train. Agrégé sur le réseau, le total resterait de l'ordre de 700 et ne dirait rien.
    // Micrometer ne garde qu'une référence FAIBLE vers l'état d'une jauge : ces AtomicInteger
    // doivent donc vivre dans le poller, sinon la jauge renverrait NaN après un GC.
    private final Map<String, AtomicInteger> journeysByLine = new ConcurrentHashMap<>();

    public RealtimePoller(PrimProperties prim, ObjectMapper objectMapper, LineRegistry registry) {
        this.prim = prim;
        this.objectMapper = objectMapper;
        this.registry = registry;
    }

    @Autowired
    public void attachMetrics(MeterRegistry meterRegistry) {
        this.meters = meterRegistry;
        this.pollFailures = meterRegistry.counter("mapidf.rt.poll.failures");
        meterRegistry.gauge("mapidf.rt.snapshot.age.seconds", snapshot,
            ref -> ref.get().asOf().equals(Instant.EPOCH)
                ? 0.0
                : Duration.between(ref.get().asOf(), Instant.now()).getSeconds());
    }

    /**
     * Nombre de courses temps réel retenues, par ligne suivie. Publié pour TOUTES les lignes du
     * registry, y compris celles absentes du flux : c'est précisément le zéro qu'on veut voir.
     */
    private void publishJourneyGauges(RtSnapshot fresh) {
        if (meters == null) {
            return;
        }
        registry.current().linesBySiriRef().forEach((siriLineRef, line) -> journeysByLine
            .computeIfAbsent(line.id(), id -> {
                AtomicInteger holder = new AtomicInteger();
                meters.gauge("mapidf.rt.journeys", List.of(Tag.of("line", id)),
                    holder, AtomicInteger::get);
                return holder;
            })
            .set(fresh.forLine(siriLineRef).size()));
    }

    public RtSnapshot current() {
        return snapshot.get();
    }

    // fixedDelay : le prochain poll ne démarre qu'après la fin du précédent → pas de
    // chevauchement ni de rafale de connexions vers PRIM si un appel traîne.
    //
    // Le flux estimated-timetable est désormais appelé SANS filtre : il couvre tout le
    // réseau (12 018 courses/1 013 lignes mesurées le 2026-07-29) en un seul appel, à quota
    // constant quel que soit le nombre de lignes suivies. Le périmètre (LineRef SIRI des
    // lignes suivies) est appliqué à la lecture, dans parse(), depuis LineRegistry.
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

    // public (et non package-private) pour permettre aux IT de contrôleur d'injecter un
    // snapshot déterministe sans appeler PRIM : le poll réel passe par poll(), planifié.
    public void pollOnce(Fetcher fetcher, Instant asOf) {
        try (InputStream body = fetcher.get(prim.realtimeBaseUrl())) {
            RtSnapshot fresh = parse(objectMapper, body, asOf, registry.trackedSiriLineRefs());
            snapshot.set(fresh);
            publishJourneyGauges(fresh);
            log.info("[RT] Poll réussi");
        } catch (Exception e) {
            if (pollFailures != null) {
                pollFailures.increment();
            }
            log.warn("[RT] Échec du poll, snapshot conservé : {}", e.getMessage());
        }
    }

    // Package-private (et non private) pour permettre à un test de vérifier le décodage gzip
    // au niveau HTTP réel, sans passer par parse() (qui, lui, ignore totalement l'encodage).
    InputStream fetch(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
            .header(prim.authHeader(), prim.apiKey())
            // Le HttpClient de Java ne négocie pas gzip tout seul et ne décompresse pas : on
            // demande explicitement et on décode. Mesuré : 3,96 Mo au lieu de 45,6 Mo (×11,5),
            // soit ~4,7 Go/jour au lieu de ~55.
            .header("Accept-Encoding", "gzip")
            .timeout(REQUEST_TIMEOUT)
            .GET()
            .build();
        HttpResponse<InputStream> response =
            httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        // On DOIT vérifier le code HTTP : sur 429 (quota) ou 5xx, PRIM renvoie un corps JSON
        // d'erreur qui se parserait en 0 course et écraserait le dernier bon snapshot.
        if (response.statusCode() / 100 != 2) {
            response.body().close();
            throw new IOException("réponse HTTP " + response.statusCode() + " de PRIM");
        }
        boolean gzipped = response.headers().firstValue("Content-Encoding")
            .map(value -> value.toLowerCase(java.util.Locale.ROOT).contains("gzip"))
            .orElse(false);
        return gzipped ? new GZIPInputStream(response.body()) : response.body();
    }

    /**
     * Parse le flux SIRI global en streaming : on avance jusqu'aux {@code
     * EstimatedVehicleJourney} et on lit UNE course à la fois en sous-arbre, gardée seulement si
     * son {@code LineRef} est suivi. Pic mémoire = une course, au lieu des 45,6 Mo du corps plus
     * l'arbre complet qu'imposait {@code readTree(byte[])}.
     *
     * <p>Un ensemble de lignes vide (registry pas encore réhydraté) ne matérialise rien : le
     * poll suivant reprendra avec un registry rempli.
     */
    static RtSnapshot parse(ObjectMapper mapper, InputStream body, Instant asOf,
                            Set<String> trackedLineRefs) {
        Map<String, List<RtSnapshot.LiveJourney>> byLine = new HashMap<>();
        try (JsonParser parser = mapper.createParser(body)) {
            while (parser.nextToken() != null) {
                if (parser.currentToken() != JsonToken.PROPERTY_NAME
                    || !"EstimatedVehicleJourney".equals(parser.currentName())) {
                    continue;
                }
                JsonToken value = parser.nextToken();
                if (value == JsonToken.START_ARRAY) {
                    while (parser.nextToken() != JsonToken.END_ARRAY) {
                        collect(parser.readValueAsTree(), trackedLineRefs, byLine);
                    }
                } else if (value == JsonToken.START_OBJECT) {
                    collect(parser.readValueAsTree(), trackedLineRefs, byLine);
                }
            }
        }
        return new RtSnapshot(asOf, byLine);
    }

    private static void collect(JsonNode journey, Set<String> trackedLineRefs,
                                Map<String, List<RtSnapshot.LiveJourney>> byLine) {
        String lineRef = journey.path("LineRef").path("value").asString("");
        if (!trackedLineRefs.contains(lineRef)) {
            return;
        }
        try {
            RtSnapshot.LiveJourney live = toJourney(journey, lineRef);
            if (live != null) {
                byLine.computeIfAbsent(lineRef, key -> new ArrayList<>()).add(live);
            }
        } catch (RuntimeException e) {
            // Une course pourrie ne doit pas faire perdre tout le snapshot — surtout en réseau
            // complet, où une seule course cassée coûterait les 705 autres.
            log.warn("[RT] Course ignorée (parse impossible) : {}", e.getMessage());
        }
    }

    private static RtSnapshot.LiveJourney toJourney(JsonNode journey, String lineRef) {
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
        String directionRef = journey.path("DirectionRef").path("value").asString("");
        String destination = firstValue(journey.path("DestinationName"));
        String recordedAtRaw = journey.path("RecordedAtTime").asString(null);
        Instant recordedAt = recordedAtRaw == null ? null : Instant.parse(recordedAtRaw);
        // Mesuré le 2026-07-29 : DatedVehicleJourneyRef est renseigné sur les 705 courses
        // métro, donc l'identité est stable entre deux polls (ce qui fait vivre l'animation
        // à 705 véhicules). Le repli composite ne sert qu'aux modes moins bien renseignés.
        String journeyRef = journey.path("DatedVehicleJourneyRef").path("value").asString(null);
        if (journeyRef == null) {
            journeyRef = lineRef + "|" + directionRef + "|" + destination + "|" + calls.getFirst().time();
        }
        return new RtSnapshot.LiveJourney(lineRef, journeyRef, directionRef, destination,
            recordedAt, calls);
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
