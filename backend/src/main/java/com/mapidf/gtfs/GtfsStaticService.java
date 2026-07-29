package com.mapidf.gtfs;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import com.mapidf.configurations.properties.PrimProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class GtfsStaticService {

    private final GtfsStaticLoader loader;
    private final PrimProperties prim;
    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(java.time.Duration.ofSeconds(15))
        .build();

    public GtfsStaticService(GtfsStaticLoader loader, PrimProperties prim) {
        this.loader = loader;
        this.prim = prim;
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
            // Le périmètre chargé vient de app.network.modes : le loader découvre les lignes
            // dans routes.txt par route_type, sans route_id en dur.
            loader.load(response.body());
            log.info("[GTFS] Réseau rechargé");
        } catch (Exception e) {
            log.error("[GTFS] Échec du refresh statique", e);
        }
    }
}
