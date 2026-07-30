package com.mapidf.gtfs;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class GtfsStaticServiceTest {

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
}
