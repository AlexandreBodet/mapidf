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
 * <p>Conséquence à connaître : un chemin non mappé n'a pas de handler, donc n'appelle pas cet
 * interceptor, donc n'est pas compté. Sans importance — un 404 ne coûte rien.
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
     */
    private static boolean isLoopback(String ip) {
        return ip.startsWith("127.") || "::1".equals(ip) || "0:0:0:0:0:0:0:1".equals(ip);
    }
}
