package com.mapidf.disruptions;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;

import com.mapidf.configurations.properties.PrimProperties;
import com.mapidf.network.LineRegistry;
import com.mapidf.network.TrackedLine;
import com.mapidf.position.PositionEngine;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Perturbations du réseau, depuis le flux {@code disruptions_bulk} de PRIM. Mesuré le
 * 2026-07-30 : un seul appel couvre tout le réseau (1029 perturbations, 710 lignes), 1,53 Mo
 * ramenés à 288 Ko en gzip — le coût quota est donc indépendant du nombre de lignes suivies,
 * comme pour {@code estimated-timetable}.
 *
 * <p>Le corps est lu en un arbre complet, sans streaming : 288 Ko ne justifient pas la
 * mécanique de {@code RealtimePoller}, qui existe pour un flux 150 fois plus gros.
 */
@Slf4j
@Component
public class DisruptionPoller {

    @FunctionalInterface
    public interface Fetcher {
        InputStream get(String url) throws Exception;
    }

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final ZoneId PARIS = ZoneId.of("Europe/Paris");

    // Les périodes du flux sont en heure locale compacte, sans fuseau : "20260729T104900".
    private static final DateTimeFormatter PERIOD_FORMAT =
        DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss", Locale.ROOT);

    private final PrimProperties prim;
    private final ObjectMapper objectMapper;
    private final LineRegistry registry;
    private final AtomicReference<DisruptionSnapshot> snapshot =
        new AtomicReference<>(DisruptionSnapshot.empty());
    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();
    private Counter pollFailures;

    public DisruptionPoller(PrimProperties prim, ObjectMapper objectMapper, LineRegistry registry) {
        this.prim = prim;
        this.objectMapper = objectMapper;
        this.registry = registry;
    }

    @Autowired
    public void attachMetrics(MeterRegistry meterRegistry) {
        this.pollFailures = meterRegistry.counter("mapidf.disruptions.poll.failures");
    }

    public DisruptionSnapshot current() {
        return snapshot.get();
    }

    @Scheduled(fixedDelayString = "${app.prim.disruptions-poll-interval}")
    public void poll() {
        if (prim.disruptionsUrl() == null || prim.disruptionsUrl().isBlank()) {
            return;
        }
        pollOnce(this::fetch, Instant.now());
    }

    // public pour qu'un IT injecte un instantané déterministe sans appeler PRIM.
    public void pollOnce(Fetcher fetcher, Instant asOf) {
        try (InputStream body = fetcher.get(prim.disruptionsUrl())) {
            snapshot.set(parse(objectMapper, body, asOf, lineIdsByRouteId()));
            log.info("[DISRUPT] Poll réussi");
        } catch (Exception e) {
            if (pollFailures != null) {
                pollFailures.increment();
            }
            log.warn("[DISRUPT] Échec du poll, instantané conservé : {}", e.getMessage());
        }
    }

    /** {@code IDFM:C01379} → {@code 9} : le flux nomme les lignes par leur route_id GTFS. */
    private Map<String, String> lineIdsByRouteId() {
        return registry.current().lines().stream()
            .collect(Collectors.toMap(TrackedLine::gtfsRouteId, TrackedLine::id, (a, b) -> a));
    }

    private InputStream fetch(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
            .header(prim.authHeader(), prim.apiKey())
            .header("Accept-Encoding", "gzip")
            .timeout(REQUEST_TIMEOUT)
            .GET()
            .build();
        HttpResponse<InputStream> response =
            httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        // Comme pour le flux temps réel : un 429 ou un 5xx renvoie un corps JSON d'erreur, qui se
        // parserait en zéro perturbation et écraserait le dernier bon instantané.
        if (response.statusCode() / 100 != 2) {
            response.body().close();
            throw new IOException("réponse HTTP " + response.statusCode() + " de PRIM");
        }
        boolean gzipped = response.headers().firstValue("Content-Encoding")
            .map(contentEncoding -> contentEncoding.toLowerCase(Locale.ROOT).contains("gzip"))
            .orElse(false);
        return gzipped ? new GZIPInputStream(response.body()) : response.body();
    }

    /**
     * Indexe les perturbations par ligne suivie et par arrêt. Les lignes non suivies (bus, RER…)
     * sont écartées ici : 710 lignes dans le flux pour 16 qui nous intéressent.
     *
     * <p>Une perturbation d'arrêt compte AUSSI pour sa ligne : « arrêt non desservi » est une
     * information de ligne pour qui regarde le sélecteur, et l'arrêt la précise.
     */
    static DisruptionSnapshot parse(ObjectMapper mapper, InputStream body, Instant asOf,
                                    Map<String, String> lineIdsByRouteId) {
        JsonNode root = mapper.readTree(body);
        Map<String, Disruption> byId = new HashMap<>();
        for (JsonNode node : root.path("disruptions")) {
            Disruption disruption = toDisruption(node);
            if (disruption != null) {
                byId.put(disruption.id(), disruption);
            }
        }

        // LinkedHashMap par clé : une même perturbation revient sur plusieurs objets impactés
        // d'une même ligne (mesuré : 38 objets sur la 13), il ne faut la garder qu'une fois.
        Map<String, Map<String, Disruption>> byLine = new HashMap<>();
        Map<String, Map<String, Disruption>> byStop = new HashMap<>();
        for (JsonNode line : root.path("lines")) {
            String lineId = lineIdsByRouteId.get(routeIdOf(line.path("id").asString("")));
            if (lineId == null) {
                continue;
            }
            for (JsonNode impacted : line.path("impactedObjects")) {
                boolean isStop = "stop_point".equals(impacted.path("type").asString(""));
                String stopKey = PositionEngine.stopKey(impacted.path("id").asString(""));
                for (JsonNode id : impacted.path("disruptionIds")) {
                    Disruption disruption = byId.get(id.asString(""));
                    if (disruption == null) {
                        continue;
                    }
                    index(byLine, lineId, disruption);
                    if (isStop && !stopKey.isEmpty()) {
                        index(byStop, stopKey, disruption);
                    }
                }
            }
        }
        return new DisruptionSnapshot(asOf, flatten(byLine), flatten(byStop));
    }

    /** {@code line:IDFM:C01379} → {@code IDFM:C01379}, le route_id GTFS du registry. */
    static String routeIdOf(String feedLineId) {
        return feedLineId.startsWith("line:") ? feedLineId.substring("line:".length()) : feedLineId;
    }

    private static void index(Map<String, Map<String, Disruption>> target, String key,
                              Disruption disruption) {
        target.computeIfAbsent(key, k -> new LinkedHashMap<>()).put(disruption.id(), disruption);
    }

    private static Map<String, List<Disruption>> flatten(Map<String, Map<String, Disruption>> source) {
        return source.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey,
            entry -> List.copyOf(entry.getValue().values())));
    }

    private static Disruption toDisruption(JsonNode node) {
        String id = node.path("id").asString(null);
        if (id == null) {
            return null;
        }
        List<Disruption.Period> periods = new ArrayList<>();
        for (JsonNode period : node.path("applicationPeriods")) {
            Instant begin = instantOf(period.path("begin").asString(null));
            Instant end = instantOf(period.path("end").asString(null));
            if (begin != null && end != null) {
                periods.add(new Disruption.Period(begin, end));
            }
        }
        if (periods.isEmpty()) {
            // Sans période, impossible de dire si c'est en cours : on préfère l'ignorer plutôt
            // que d'afficher une perturbation éternelle.
            return null;
        }
        return new Disruption(id,
            Disruption.Severity.fromFeed(node.path("severity").asString(null)),
            node.path("cause").asString(""),
            node.path("title").asString(""),
            node.path("shortMessage").asString(""),
            toPlainText(node.path("message").asString("")),
            periods);
    }

    /**
     * HTML du flux → texte brut. Les sauts de ligne structurants sont conservés, le reste des
     * balises tombe, et les entités sont décodées ({@code P&#233;riode} → « Période »).
     */
    static String toPlainText(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        String withBreaks = html.replaceAll("(?i)<br\\s*/?>|</p\\s*>", "\n");
        String stripped = withBreaks.replaceAll("<[^>]*>", "");
        return HtmlUtils.htmlUnescape(stripped)
            .replaceAll("[ \\t\\u00A0]+", " ")
            .replaceAll(" ?\n ?", "\n")
            .replaceAll("\n{2,}", "\n")
            .strip();
    }

    private static Instant instantOf(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return LocalDateTime.parse(raw, PERIOD_FORMAT).atZone(PARIS).toInstant();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
