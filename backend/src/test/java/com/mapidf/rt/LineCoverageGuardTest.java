package com.mapidf.rt;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import com.mapidf.configurations.properties.PrimProperties;
import com.mapidf.network.LineRegistry;
import com.mapidf.network.NetworkSnapshot;
import com.mapidf.network.TrackedLine;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deux lignes suivies pour un flux qui n'en contient qu'une : exactement le cas à détecter — une
 * ligne à zéro course alors que le réseau, lui, circule. Les flux sont écrits ici plutôt que dans
 * RtFixtures, dont les échantillons contiennent toujours toutes leurs lignes.
 */
class LineCoverageGuardTest {

    private static final String NINE = "STIF:Line::C01379:";
    private static final String EIGHT = "STIF:Line::C01378:";

    // 14 h à Paris : en pleine plage de service (05h30–01h30).
    private static final Instant MIDDAY =
        ZonedDateTime.of(2026, 7, 30, 14, 0, 0, 0, ZoneId.of("Europe/Paris")).toInstant();

    private static String feedFor(String... lineRefs) {
        String journeys = String.join(",", List.of(lineRefs).stream().map(ref -> """
            {"LineRef":{"value":"%s"},"DirectionRef":{"value":"0"},
             "DatedVehicleJourneyRef":{"value":"J-%s"},"DestinationName":[{"value":"Terminus"}],
             "EstimatedCalls":{"EstimatedCall":[{"StopPointRef":{"value":"STIF:StopPoint:Q:2:"},
               "ExpectedDepartureTime":"2026-07-30T12:05:00.000Z","DepartureStatus":"ON_TIME"}]}}
            """.formatted(ref, ref)).toList());
        return """
            {"Siri":{"ServiceDelivery":{"EstimatedTimetableDelivery":[
              {"EstimatedJourneyVersionFrame":[{"EstimatedVehicleJourney":[%s]}]}]}}}
            """.formatted(journeys);
    }

    private final LineRegistry registry = registryTracking(NINE, EIGHT);
    private final RealtimePoller poller =
        new RealtimePoller(new PrimProperties("", "apikey", "", "http://rt", Duration.ofSeconds(10),
            "", Duration.ofMinutes(5)), new ObjectMapper(), registry);
    private final LineCoverageGuard guard = new LineCoverageGuard(registry, poller);

    private static LineRegistry registryTracking(String... siriLineRefs) {
        LineRegistry registry = new LineRegistry();
        registry.publish(NetworkSnapshot.of(List.of(siriLineRefs).stream()
            .map(ref -> new TrackedLine(ref, ref, ref, ref, "#000000", "METRO", List.of()))
            .toList(), List.of()));
        return registry;
    }

    private void feed(Instant asOf, String... lineRefs) {
        byte[] body = feedFor(lineRefs).getBytes(StandardCharsets.UTF_8);
        poller.pollOnce(url -> new ByteArrayInputStream(body), asOf);
    }

    private List<String> at(long minutes) {
        return guard.check(MIDDAY.plus(Duration.ofMinutes(minutes)));
    }

    @Test
    void staysSilentWhileTheZeroIsShorterThanTheTolerance() {
        feed(MIDDAY, NINE);

        assertThat(at(0)).isEmpty();
        assertThat(at(14)).isEmpty();
    }

    @Test
    void reportsALineLeftAtZeroWhileTheRestOfTheNetworkRuns() {
        feed(MIDDAY, NINE);
        at(0);

        assertThat(at(16)).containsExactly(EIGHT);
    }

    @Test
    void reportsOnlyOnceForTheSameOutage() {
        feed(MIDDAY, NINE);
        at(0);
        at(16);

        // Sinon un WARN par minute noierait le log, et la deuxième heure de panne en dirait
        // moins que la première.
        assertThat(at(20)).isEmpty();
    }

    @Test
    void reportsAgainAfterTheLineCameBackAndFellSilentOnceMore() {
        feed(MIDDAY, NINE);
        at(0);
        assertThat(at(16)).containsExactly(EIGHT);

        feed(MIDDAY.plus(Duration.ofMinutes(20)), NINE, EIGHT);
        assertThat(at(20)).isEmpty();

        feed(MIDDAY.plus(Duration.ofMinutes(25)), NINE);
        at(25);
        assertThat(at(45)).containsExactly(EIGHT);
    }

    @Test
    void neverBlamesALineWhenTheWholeNetworkIsAtZero() {
        // JSON valide sans aucune course : c'est le flux qui est tombé, pas une ligne. Le
        // compteur d'échecs de poll et l'âge du snapshot couvrent déjà ce cas.
        poller.pollOnce(url -> new ByteArrayInputStream("{}".getBytes(StandardCharsets.UTF_8)), MIDDAY);
        at(0);

        assertThat(at(30)).isEmpty();
    }

    @Test
    void staysSilentOutsideServiceHours() {
        // 3 h du matin : le poller ne tourne pas, tout est à zéro par construction.
        feed(MIDDAY, NINE);
        at(0);

        assertThat(guard.check(
            ZonedDateTime.of(2026, 7, 31, 3, 0, 0, 0, ZoneId.of("Europe/Paris")).toInstant()))
            .isEmpty();
    }
}
