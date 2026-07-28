package com.mapidf.gtfs;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import com.mapidf.configurations.properties.LineProperties;
import com.mapidf.configurations.properties.PrimProperties;
import com.mapidf.data.repositories.RouteRepository;
import com.mapidf.position.ScheduleProvider;
import lombok.extern.slf4j.Slf4j;
import org.locationtech.jts.geom.LineString;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class GtfsStaticService {

    private final GtfsStaticLoader loader;
    private final RouteRepository routeRepository;
    private final PrimProperties prim;
    private final LineProperties line;
    private final ScheduleProvider scheduleProvider;
    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(java.time.Duration.ofSeconds(15))
        .build();

    private volatile LineString routeGeometry;

    public GtfsStaticService(GtfsStaticLoader loader, RouteRepository routeRepository,
                             PrimProperties prim, LineProperties line, ScheduleProvider scheduleProvider) {
        this.loader = loader;
        this.routeRepository = routeRepository;
        this.prim = prim;
        this.line = line;
        this.scheduleProvider = scheduleProvider;
    }

    @Scheduled(initialDelay = 0, fixedRateString = "P1D")
    public void refresh() {
        if (prim.gtfsStaticUrl() == null || prim.gtfsStaticUrl().isBlank()) {
            log.info("[GTFS] URL statique non configurée, refresh ignoré");
            return;
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(prim.gtfsStaticUrl()))
                .header(prim.authHeader(), prim.apiKey())
                .GET()
                .build();
            HttpResponse<java.io.InputStream> response =
                httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            loader.loadFromZip(response.body(), line.gtfsRouteId());
            cacheGeometry();
            scheduleProvider.invalidate();
            log.info("[GTFS] Réseau ligne {} rechargé", line.gtfsRouteId());
        } catch (Exception e) {
            log.error("[GTFS] Échec du refresh statique", e);
        }
    }

    public void cacheGeometry() {
        this.routeGeometry = routeRepository.findByGtfsId(line.gtfsRouteId())
            .map(r -> r.getGeom())
            .orElse(null);
    }

    public LineString getRouteGeometry() {
        return routeGeometry;
    }
}
