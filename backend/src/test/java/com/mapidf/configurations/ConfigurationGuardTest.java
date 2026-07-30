package com.mapidf.configurations;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.assertThat;

class ConfigurationGuardTest {

    private static final String[] COMPLETE = {
        "spring.datasource.url=jdbc:postgresql://localhost:5432/mapidf",
        "spring.datasource.username=mapidf",
        "spring.datasource.password=un-mot-de-passe",
        "app.prim.api-key=une-cle",
    };

    private final ApplicationContextRunner runner =
        new ApplicationContextRunner().withUserConfiguration(ConfigurationGuard.class);

    @Test
    void startsWhenEveryRequiredValueIsProvided() {
        runner.withPropertyValues(COMPLETE).run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void refusesToStartAndNamesTheMissingValue() {
        runner.withPropertyValues(
            "spring.datasource.url=jdbc:postgresql://localhost:5432/mapidf",
            "spring.datasource.username=mapidf",
            "app.prim.api-key=une-cle"
        ).run(context -> assertThat(context).hasFailed()
            .getFailure().hasMessageContaining("spring.datasource.password")
            .hasMessageContaining(".env"));
    }

    /**
     * Le cas réel : sans .env, application.yml livre le texte {@code ${POSTGRES_PASSWORD}} tel
     * quel au Binder. C'est CE cas que le garde-fou existe pour attraper.
     */
    @Test
    void refusesToStartOnAPlaceholderThatWasNeverResolved() {
        runner.withPropertyValues(
            "spring.datasource.url=jdbc:postgresql://localhost:5432/mapidf",
            "spring.datasource.username=mapidf",
            "spring.datasource.password=${POSTGRES_PASSWORD}",
            "app.prim.api-key=une-cle"
        ).run(context -> assertThat(context).hasFailed()
            .getFailure().hasMessageContaining("spring.datasource.password"));
    }

    /**
     * L'ancien défaut {@code ${PRIM_API_KEY:}} valait chaîne vide et laissait démarrer une appli
     * qui ne pouvait rien afficher : une valeur blanche vaut une valeur absente.
     */
    @Test
    void treatsABlankValueAsMissing() {
        runner.withPropertyValues(
            "spring.datasource.url=jdbc:postgresql://localhost:5432/mapidf",
            "spring.datasource.username=mapidf",
            "spring.datasource.password=un-mot-de-passe",
            "app.prim.api-key=   "
        ).run(context -> assertThat(context).hasFailed()
            .getFailure().hasMessageContaining("app.prim.api-key"));
    }
}
