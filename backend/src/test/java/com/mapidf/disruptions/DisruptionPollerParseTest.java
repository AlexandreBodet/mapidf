package com.mapidf.disruptions;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Map;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Structure copiée du flux réel mesuré le 2026-07-30 : une perturbation de ligne en cours, une
 * de travaux à venir, une d'arrêt, et une ligne de bus non suivie.
 */
class DisruptionPollerParseTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String FEED = """
        {
          "lastUpdatedDate": "20260730T173000",
          "disruptions": [
            {"id": "en-cours", "cause": "PERTURBATION", "severity": "PERTURBEE",
             "title": "Métro 9 : Incident - Trafic perturbé", "shortMessage": "Trafic perturbé",
             "message": "<p>du HTML qu'on ne rend pas</p>",
             "applicationPeriods": [{"begin": "20260730T170000", "end": "20260731T043000"}]},
            {"id": "travaux-aout", "cause": "TRAVAUX", "severity": "BLOQUANTE",
             "title": "Métro 9 : Travaux - Trafic interrompu", "shortMessage": "Trafic interrompu",
             "applicationPeriods": [{"begin": "20260820T044500", "end": "20260828T043000"}]},
            {"id": "arret", "cause": "TRAVAUX", "severity": "BLOQUANTE",
             "title": "Métro 9 : Arrêt non desservi", "shortMessage": "Arrêt non desservi",
             "applicationPeriods": [{"begin": "20260730T160000", "end": "20260730T200000"}]},
            {"id": "sans-periode", "cause": "INFORMATION", "severity": "INFORMATION",
             "title": "Sans période", "shortMessage": "", "applicationPeriods": []},
            {"id": "bus", "cause": "TRAVAUX", "severity": "BLOQUANTE",
             "title": "Bus 346 : Travaux", "shortMessage": "Arrêt non desservi",
             "applicationPeriods": [{"begin": "20260730T160000", "end": "20260730T200000"}]}
          ],
          "lines": [
            {"id": "line:IDFM:C01379", "shortName": "9", "mode": "Metro", "impactedObjects": [
              {"type": "line", "id": "line:IDFM:C01379", "disruptionIds": ["en-cours", "travaux-aout"]},
              {"type": "stop_point", "id": "stop_point:IDFM:463221", "name": "Alma",
               "disruptionIds": ["arret", "en-cours"]},
              {"type": "stop_point", "id": "stop_point:IDFM:463222", "name": "Iéna",
               "disruptionIds": ["sans-periode"]}
            ]},
            {"id": "line:IDFM:C01345", "shortName": "559", "mode": "Bus", "impactedObjects": [
              {"type": "line", "id": "line:IDFM:C01345", "disruptionIds": ["bus"]}
            ]}
          ]
        }
        """;

    private static final Map<String, String> TRACKED = Map.of("IDFM:C01379", "9");

    private static final Instant NOW =
        ZonedDateTime.of(2026, 7, 30, 18, 0, 0, 0, ZoneId.of("Europe/Paris")).toInstant();

    private static DisruptionSnapshot parse() {
        return DisruptionPoller.parse(MAPPER,
            new ByteArrayInputStream(FEED.getBytes(StandardCharsets.UTF_8)), NOW, TRACKED);
    }

    @Test
    void keepsOnlyTrackedLines() {
        // 710 lignes dans le flux réel pour 16 suivies : le bus ne doit pas entrer.
        assertThat(parse().byLine()).containsOnlyKeys("9");
    }

    @Test
    void indexesALineDisruptionAndAStopDisruptionUnderTheirLine() {
        assertThat(parse().byLine().get("9"))
            .extracting(Disruption::id)
            .containsExactlyInAnyOrder("en-cours", "travaux-aout", "arret");
    }

    @Test
    void doesNotRepeatADisruptionAttachedToSeveralImpactedObjects() {
        // "en-cours" est porté par la ligne ET par un arrêt : une seule occurrence attendue.
        assertThat(parse().byLine().get("9"))
            .filteredOn(disruption -> disruption.id().equals("en-cours"))
            .hasSize(1);
    }

    @Test
    void indexesStopsByNormalizedKey() {
        assertThat(parse().byStop()).containsKey("463221");
        assertThat(parse().byStop().get("463221")).extracting(Disruption::id)
            .containsExactlyInAnyOrder("arret", "en-cours");
    }

    @Test
    void ignoresADisruptionWithoutAnyPeriod() {
        assertThat(parse().byStop()).doesNotContainKey("463222");
        assertThat(parse().byLine().get("9")).extracting(Disruption::id)
            .doesNotContain("sans-periode");
    }

    @Test
    void servesOnlyDisruptionsRunningAtTheRequestedInstant() {
        // 18 h le 30 juillet : l'incident et l'arrêt sont en cours, les travaux d'août non.
        assertThat(parse().forLine("9", NOW)).extracting(Disruption::id)
            .containsExactly("arret", "en-cours");
    }

    @Test
    void ordersTheWorstSeverityFirst() {
        assertThat(parse().forLine("9", NOW).getFirst().severity())
            .isEqualTo(Disruption.Severity.BLOQUANTE);
    }

    @Test
    void reducesTheFeedHtmlToPlainText() {
        // Cas réel mesuré : tout le sens d'un « Information - Autre » est dans le message.
        assertThat(DisruptionPoller.toPlainText(
            "<p>P&#233;riode : du 01 au 31 juillet.<br><br>Privil&#233;giez la ligne 14.</p>"))
            .isEqualTo("Période : du 01 au 31 juillet.\nPrivilégiez la ligne 14.");
    }

    @Test
    void leavesNoMarkupInThePlainText() {
        assertThat(DisruptionPoller.toPlainText("<p onclick=\"x\">a<b>b</b><script>c</script></p>"))
            .doesNotContain("<").doesNotContain(">");
    }

    @Test
    void turnsAnAbsentMessageIntoAnEmptyDetail() {
        assertThat(DisruptionPoller.toPlainText(null)).isEmpty();
        assertThat(DisruptionPoller.toPlainText("   ")).isEmpty();
    }

    @Test
    void carriesTheDetailOnTheParsedDisruption() {
        assertThat(parse().forLine("9", NOW))
            .filteredOn(disruption -> disruption.id().equals("en-cours"))
            .singleElement()
            .extracting(Disruption::detail)
            .isEqualTo("du HTML qu'on ne rend pas");
    }

    @Test
    void readsTheFeedSeverityAndFallsBackOnUnknownValues() {
        assertThat(Disruption.Severity.fromFeed("BLOQUANTE")).isEqualTo(Disruption.Severity.BLOQUANTE);
        assertThat(Disruption.Severity.fromFeed("nouvelle-valeur")).isEqualTo(Disruption.Severity.INCONNUE);
        assertThat(Disruption.Severity.fromFeed(null)).isEqualTo(Disruption.Severity.INCONNUE);
    }

    @Test
    void stripsTheFeedPrefixToRecoverTheGtfsRouteId() {
        assertThat(DisruptionPoller.routeIdOf("line:IDFM:C01379")).isEqualTo("IDFM:C01379");
        assertThat(DisruptionPoller.routeIdOf("IDFM:C01379")).isEqualTo("IDFM:C01379");
    }
}
