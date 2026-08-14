package com.mapidf.controllers.support;

import java.time.Clock;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.mapidf.configurations.properties.RateLimitProperties;
import com.mapidf.data.enums.ErrorCode;
import com.mapidf.exceptions.ApiException;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Traduit un refus du {@link RateLimiter} en 429.
 *
 * <p>Interceptor et non Filter : une exception levée dans un Filter se produit hors du
 * DispatcherServlet, donc l'ApiExceptionHandler ne la voit pas et il faudrait réécrire à la main
 * la sérialisation d'ErrorResponse — une seconde implémentation du format d'erreur, vouée à
 * diverger de la première.
 *
 * <p>Conséquence à connaître : {@code spring.web.resources.add-mappings} (vrai par défaut, non
 * désactivé ici) fait couvrir {@code /**} par un {@code ResourceHttpRequestHandler} — un chemin
 * non mappé a donc bien un handler, et cet interceptor s'y applique comme sur un
 * {@code @RestController} (vérifié par {@code RateLimitIT#compteAussiUnCheminNonMappe} : après
 * le budget, {@code /nope} répond 429, pas 404). Sans conséquence pratique : un client qui ne
 * sait pas viser un endpoint réel n'a pas plus de budget qu'un autre.
 */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private final RateLimiter limiter;

    public RateLimitInterceptor(Clock clock, RateLimitProperties properties, MeterRegistry meters) {
        this.limiter = new RateLimiter(clock, properties.requestsPerMinute(), meters);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String ip = request.getRemoteAddr();
        if (ip == null || isLoopback(ip)) {
            return true;
        }

        RateLimiter.Decision decision = limiter.check(ip);
        if (decision.allowed()) {
            return true;
        }

        // Posé AVANT de lever : l'ApiExceptionHandler n'appelle que setStatus et setContentType,
        // donc la réponse n'est pas encore validée et l'en-tête survit.
        response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(decision.retryAfterSeconds()));
        throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, ErrorCode.TOO_MANY_REQUESTS);
    }

    /**
     * 127.0.0.0/8 et ::1 : après résolution du X-Forwarded-For, c'est la machine elle-même et
     * jamais un client public. Pas InetAddress.getByName — sur autre chose qu'un littéral, il
     * ferait une résolution DNS à chaque requête.
     *
     * <p>Limite de la garantie : le défaut de {@code server.tomcat.remoteip.internal-proxies}
     * couvre aussi {@code 100.64.0.0/10} (CGNAT). Le {@code RemoteIpValve} retient l'entrée la
     * plus à gauche de X-Forwarded-For quand toute la chaîne est interne : l'exemption est donc
     * forgeable par quiconque a, vu de la bordure, une adresse déjà privée — un pair du LAN, un
     * conteneur voisin du bridge Compose, un pod du cluster — jamais par un client public. Le
     * chemin est vivant aujourd'hui via nginx (port 8080, toutes interfaces), pas via le port
     * 8100. Assumé : qui est déjà dans le périmètre privé a d'autres portes, et resserrer la
     * liste échangerait ce trou improbable contre un risque de seau global si un saut amont
     * légitime siégeait un jour dans une plage retirée.
     */
    private static boolean isLoopback(String ip) {
        return ip.startsWith("127.") || "::1".equals(ip) || "0:0:0:0:0:0:0:1".equals(ip);
    }
}
