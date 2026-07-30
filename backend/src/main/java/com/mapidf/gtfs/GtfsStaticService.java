package com.mapidf.gtfs;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import com.mapidf.configurations.properties.PrimProperties;
import com.mapidf.network.LineRegistry;
import com.mapidf.network.NetworkRegistryBuilder;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class GtfsStaticService {

    private final GtfsStaticLoader loader;
    private final PrimProperties prim;
    private final NetworkRegistryBuilder registryBuilder;
    private final LineRegistry registry;
    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .build();

    // Attente de la RÉPONSE, pas du transfert : avec ofInputStream, send() rend la main dès les
    // en-têtes, donc ce délai borne un serveur qui ne répond pas — pas les 125 Mo qui suivent.
    private static final Duration RESPONSE_TIMEOUT = Duration.ofMinutes(1);

    private static final int NOT_MODIFIED = 304;

    // Validateurs de l'archive chargée avec succès, pour ne pas retélécharger 125 Mo inchangés
    // chaque jour. Mesuré le 2026-07-30 : le miroir honore If-None-Match ET If-Modified-Since,
    // et renvoie bien 304. En mémoire seulement — un redémarrage refait un téléchargement
    // complet, ce qui coûte une ligne de code au lieu d'une table.
    private volatile String etag;
    private volatile String lastModified;

    public GtfsStaticService(GtfsStaticLoader loader, PrimProperties prim,
                              NetworkRegistryBuilder registryBuilder, LineRegistry registry) {
        this.loader = loader;
        this.prim = prim;
        this.registryBuilder = registryBuilder;
        this.registry = registry;
    }

    private static final String PRIM_HOST = "prim.iledefrance-mobilites.fr";

    /**
     * La clé PRIM ne part que vers PRIM. L'URL statique par défaut est un miroir open data
     * (OpenDataSoft) qui n'en a pas besoin : l'y envoyer exposait le secret à un tiers.
     */
    static boolean requiresPrimKey(String url) {
        if (url == null) {
            return false;
        }
        try {
            String host = URI.create(url).getHost();
            // Égalité ou vrai sous-domaine : un endsWith nu accepterait evilprim.iledefrance-....
            return host != null && (host.equals(PRIM_HOST) || host.endsWith("." + PRIM_HOST));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Requête de téléchargement du GTFS. Extraite (et statique) pour être vérifiable sans
     * réseau : la clé n'est posée que pour PRIM, et le validateur connu — ETag en priorité,
     * plus fort que la date — évite de retélécharger une archive inchangée.
     */
    static HttpRequest gtfsRequest(PrimProperties prim, String etag, String lastModified) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(prim.gtfsStaticUrl()))
            .timeout(RESPONSE_TIMEOUT)
            .GET();
        if (requiresPrimKey(prim.gtfsStaticUrl())) {
            request.header(prim.authHeader(), prim.apiKey());
        }
        if (etag != null) {
            request.header("If-None-Match", etag);
        } else if (lastModified != null) {
            request.header("If-Modified-Since", lastModified);
        }
        return request.build();
    }

    @Scheduled(initialDelay = 0, fixedRateString = "P1D")
    public void refresh() {
        if (prim.gtfsStaticUrl() == null || prim.gtfsStaticUrl().isBlank()) {
            log.info("[GTFS] URL statique non configurée, refresh ignoré");
            return;
        }
        try {
            HttpResponse<InputStream> response = httpClient.send(
                gtfsRequest(prim, etag, lastModified), HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() == NOT_MODIFIED) {
                response.body().close();
                log.info("[GTFS] Archive inchangée, aucun téléchargement");
                return;
            }
            // Sans ce contrôle, une réponse d'erreur (ou une redirection, que ce client ne suit
            // pas) partait au parseur ZIP et ressortait en « Échec du refresh » muet sur la cause.
            if (response.statusCode() / 100 != 2) {
                response.body().close();
                throw new IOException("réponse HTTP " + response.statusCode() + " du GTFS statique");
            }
            // Le périmètre chargé vient de app.network.modes : le loader découvre les lignes
            // dans routes.txt par route_type, sans route_id en dur.
            try (InputStream body = response.body()) {
                loader.load(body);
            }
            publishFromDatabase();
            // Mémorisés seulement maintenant : retenir les validateurs d'une archive qui n'a pas
            // fini de se charger ferait sauter le rechargement suivant par un 304 mensonger.
            etag = response.headers().firstValue("ETag").orElse(null);
            lastModified = response.headers().firstValue("Last-Modified").orElse(null);
            log.info("[GTFS] Réseau rechargé et registry republié");
        } catch (Exception e) {
            log.error("[GTFS] Échec du refresh statique", e);
        }
    }

    /**
     * Republie le registry depuis PostGIS, sans accès réseau. Appelé au démarrage : un
     * redémarrage ne doit pas imposer de retélécharger 109 Mo de GTFS.
     */
    public void publishFromDatabase() {
        registry.publish(registryBuilder.build());
    }

    /**
     * Réhydrate le registry dès le démarrage, avant que le refresh quotidien n'ait abouti :
     * l'API répond immédiatement avec le dernier réseau connu au lieu de renvoyer des 404
     * pendant le téléchargement.
     */
    @PostConstruct
    void hydrateOnStartup() {
        try {
            publishFromDatabase();
        } catch (Exception e) {
            log.warn("[GTFS] Réhydratation au démarrage impossible (base vide ?) : {}", e.getMessage());
        }
    }
}
