package com.mapidf.configurations;

import java.util.List;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Refuse de démarrer si un identifiant manque. Mesuré le 2026-07-30, sans .env ni variables
 * d'environnement : une base absente échoue déjà, mais sur {@code 'url' must start with "jdbc"}
 * — le placeholder non résolu arrive tel quel chez Hikari, et le message ne nomme pas la
 * variable à renseigner. Surtout, une clé PRIM absente ne fait RIEN échouer : l'appli démarre,
 * PRIM répond 401, le poller l'avale dans son try/catch et {@code /vehicles} sert zéro véhicule
 * — une carte vide sans explication.
 *
 * <p>{@link BeanFactoryPostProcessor} et non {@code @PostConstruct} : il s'exécute avant
 * l'instanciation des singletons, donc avant que Flyway n'ouvre une connexion.
 */
@Configuration(proxyBeanMethods = false)
public class ConfigurationGuard {

    static final List<String> REQUIRED = List.of(
        "spring.datasource.url",
        "spring.datasource.username",
        "spring.datasource.password",
        "app.prim.api-key");

    @Bean
    static BeanFactoryPostProcessor requiredConfigurationGuard(Environment environment) {
        return beanFactory -> {
            List<String> missing = REQUIRED.stream()
                .filter(key -> !isProvided(environment, key))
                .toList();
            if (!missing.isEmpty()) {
                throw new IllegalStateException("Configuration incomplète, valeurs absentes : "
                    + missing + ". À renseigner dans le .env à la racine (cf. .env.example) ou "
                    + "dans l'environnement.");
            }
        };
    }

    /**
     * Un placeholder non résolu fait lever {@code getProperty} — contrairement au Binder des
     * {@code @ConfigurationProperties}, qui en garde le texte littéral. C'est cette différence
     * qui rend le garde-fou nécessaire, et c'est elle qu'on exploite ici.
     */
    private static boolean isProvided(Environment environment, String key) {
        try {
            String value = environment.getProperty(key);
            return value != null && !value.isBlank();
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
