package com.mapidf.gtfs;

import java.time.Duration;

import com.mapidf.configurations.properties.PrimProperties;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class GtfsStaticServiceTest {

    private static PrimProperties prim(String gtfsUrl) {
        return new PrimProperties("une-cle", "apikey", gtfsUrl, "", Duration.ofSeconds(60),
            "", Duration.ofMinutes(5));
    }

    private static final String MIRROR = "https://eu.ftp.opendatasoft.com/stif/GTFS/IDFM-gtfs.zip";

    @Test
    void sendsThePrimKeyOnlyToPrim() {
        assertThat(GtfsStaticService.requiresPrimKey(
            "https://prim.iledefrance-mobilites.fr/marketplace/gtfs")).isTrue();
        // URL par défaut du projet : miroir open data, aucune authentification attendue.
        assertThat(GtfsStaticService.requiresPrimKey(
            "https://eu.ftp.opendatasoft.com/stif/GTFS/IDFM-gtfs.zip")).isFalse();
        assertThat(GtfsStaticService.requiresPrimKey(
            "https://data.iledefrance-mobilites.fr/explore/dataset/gtfs")).isFalse();
    }

    @Test
    void rejectsHostsThatMerelyFinishLikePrim() {
        // Le piège d'un endsWith naïf : ces deux hôtes ne sont pas PRIM.
        assertThat(GtfsStaticService.requiresPrimKey(
            "https://evilprim.iledefrance-mobilites.fr/gtfs")).isFalse();
        assertThat(GtfsStaticService.requiresPrimKey(
            "https://prim.iledefrance-mobilites.fr.attacker.test/gtfs")).isFalse();
    }

    @Test
    void acceptsASubdomainOfPrim() {
        assertThat(GtfsStaticService.requiresPrimKey(
            "https://data.prim.iledefrance-mobilites.fr/gtfs")).isTrue();
    }

    @Test
    void treatsAnUnusableUrlAsNotPrim() {
        assertThat(GtfsStaticService.requiresPrimKey("pas une url")).isFalse();
        assertThat(GtfsStaticService.requiresPrimKey(null)).isFalse();
    }

    @Test
    void firstDownloadIsUnconditionalAndBounded() {
        var request = GtfsStaticService.gtfsRequest(prim(MIRROR), null, null);

        assertThat(request.headers().firstValue("If-None-Match")).isEmpty();
        assertThat(request.headers().firstValue("If-Modified-Since")).isEmpty();
        assertThat(request.headers().firstValue("apikey")).isEmpty();
        // Sans timeout, une requête sans réponse suspend le refresh quotidien indéfiniment.
        assertThat(request.timeout()).isPresent();
    }

    @Test
    void reusesTheKnownEtagToAvoidRedownloadingAnUnchangedArchive() {
        var request = GtfsStaticService.gtfsRequest(prim(MIRROR), "\"6a6b656d-77145fb\"", null);

        assertThat(request.headers().firstValue("If-None-Match")).hasValue("\"6a6b656d-77145fb\"");
    }

    @Test
    void fallsBackOnTheDateWhenNoEtagIsKnown() {
        var request = GtfsStaticService.gtfsRequest(prim(MIRROR), null, "Thu, 30 Jul 2026 14:53:33 GMT");

        assertThat(request.headers().firstValue("If-Modified-Since"))
            .hasValue("Thu, 30 Jul 2026 14:53:33 GMT");
    }

    @Test
    void prefersTheEtagOverTheDateWhenBothAreKnown() {
        // L'ETag est le validateur fort ; envoyer les deux serait redondant.
        var request = GtfsStaticService.gtfsRequest(prim(MIRROR), "\"abc\"", "Thu, 30 Jul 2026 14:53:33 GMT");

        assertThat(request.headers().firstValue("If-None-Match")).hasValue("\"abc\"");
        assertThat(request.headers().firstValue("If-Modified-Since")).isEmpty();
    }

    @Test
    void carriesThePrimKeyOnlyWhenTheUrlIsPrim() {
        var toPrim = GtfsStaticService.gtfsRequest(
            prim("https://prim.iledefrance-mobilites.fr/marketplace/gtfs"), null, null);

        assertThat(toPrim.headers().firstValue("apikey")).hasValue("une-cle");
    }
}
