package com.mapidf.configurations.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Budget de requêtes par IP et par minute (SEC-3). Valeur littérale dans l'application.yml et
 * non variable du .env : c'est un réglage fonctionnel, pas une coordonnée d'infrastructure, et
 * l'inscrire au .env.example la rendrait obligatoire au démarrage (ConfigurationGuard).
 */
@ConfigurationProperties(prefix = "app.ratelimit")
public record RateLimitProperties(int requestsPerMinute) {

    public RateLimitProperties {
        if (requestsPerMinute <= 0) {
            throw new IllegalArgumentException(
                "app.ratelimit.requests-per-minute doit être > 0 (reçu : " + requestsPerMinute + ")");
        }
    }
}
