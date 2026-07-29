package com.mapidf.position;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import com.mapidf.network.LineBranch;
import com.mapidf.network.TrackedLine;
import com.mapidf.rt.RtSnapshot;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.linearref.LengthIndexedLine;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Placement des véhicules sur le tracé. Le moteur reçoit désormais une {@link TrackedLine} et
 * choisit la branche avant d'interpoler : un tracé unique par ligne place mal les trains des
 * branches divergentes (mesuré : 1547 m d'écart sur la branche Ivry de la ligne 7).
 */
@Slf4j
@Component
public class PositionEngine {

    // Le MeterRegistry est gardé (et non deux Counter résolus une fois) parce que les deux
    // compteurs sont taggés PAR LIGNE, comme la spec le demande : agrégés sur tout le réseau, ils
    // ne diraient pas QUELLE ligne dégrade. counter(name, tags) est un lookup en table de
    // hachage, et ces deux chemins sont rares (0,6 % du flux mesuré).
    private MeterRegistry meters;

    @Autowired
    public void attachMetrics(MeterRegistry meterRegistry) {
        this.meters = meterRegistry;
    }

    private void count(String name, TrackedLine line) {
        if (meters != null) {
            meters.counter(name, "line", line.id()).increment();
        }
    }

    public List<Vehicle> computeAll(TrackedLine line, List<RtSnapshot.LiveJourney> journeys, Instant now) {
        List<Vehicle> out = new ArrayList<>();
        for (RtSnapshot.LiveJourney journey : journeys) {
            Vehicle vehicle = compute(line, journey, now);
            if (vehicle != null) {
                out.add(vehicle);
            } else {
                // Mesuré : 0,6 % du flux métro après couverture gloutonne. La dégradation reste
                // mesurable au lieu d'être silencieuse.
                count("mapidf.position.unplaced", line);
            }
        }
        return out;
    }

    private Vehicle compute(TrackedLine line, RtSnapshot.LiveJourney journey, Instant now) {
        List<RtSnapshot.LiveJourney.Call> sorted = journey.calls().stream()
            .sorted(Comparator.comparing(RtSnapshot.LiveJourney.Call::time))
            .toList();
        // Arrêt imminent = premier encore à venir ; s'il n'y en a plus, le dernier connu.
        // On n'exclut jamais un train qui a des données.
        RtSnapshot.LiveJourney.Call next = sorted.stream()
            .filter(call -> !call.time().isBefore(now))
            .findFirst()
            .orElse(sorted.getLast());
        RtSnapshot.LiveJourney.Call prev = sorted.stream()
            .filter(call -> call.time().isBefore(now))
            .reduce((a, b) -> b)
            .orElse(null);

        String nextKey = stopKey(next.stopRef());
        LineBranch branch = pickBranch(line, nextKey, journey.destination());
        if (branch == null) {
            return null;
        }
        List<StopOnLine> stops = branch.stops();
        int nextIdx = branch.indexOf(nextKey);
        StopOnLine to = stops.get(nextIdx);
        Vehicle.Confidence confidence = journey.calls().size() == 1
            ? Vehicle.Confidence.APPROXIMATE
            : Vehicle.Confidence.RELIABLE;

        if (nextIdx == 0) {
            // Prochain arrêt = tête de branche → placé à l'origine.
            StopOnLine after = stops.size() > 1 ? stops.get(1) : to;
            return vehicleAt(line, branch, journey, to, next, confidence,
                to.distanceAlongLine(), to.distanceAlongLine(), after.distanceAlongLine());
        }

        // Origine du segment : le dernier arrêt SIRI passé s'il est en amont sur cette branche
        // (interpolation aux VRAIES heures → capte le temps à quai) ; sinon l'arrêt précédent
        // du tracé, dont on estime l'heure de départ via l'horaire théorique.
        int prevIdx = prev == null ? -1 : branch.indexOf(stopKey(prev.stopRef()));
        double fromDist;
        Instant fromTime;
        if (prev != null && prevIdx >= 0 && prevIdx < nextIdx) {
            fromDist = stops.get(prevIdx).distanceAlongLine();
            fromTime = prev.time();
        } else {
            StopOnLine routePrev = stops.get(nextIdx - 1);
            int segmentSec = to.scheduledSec() - routePrev.scheduledSec();
            fromDist = routePrev.distanceAlongLine();
            fromTime = next.time().minusSeconds(Math.max(1, segmentSec));
        }

        long total = Duration.between(fromTime, next.time()).getSeconds();
        double fraction = total > 0
            ? clamp((double) Duration.between(fromTime, now).getSeconds() / total, 0.0, 1.0)
            : 1.0;
        double distance = fromDist + fraction * (to.distanceAlongLine() - fromDist);
        return vehicleAt(line, branch, journey, to, next, confidence,
            distance, fromDist, to.distanceAlongLine());
    }

    private Vehicle vehicleAt(TrackedLine line, LineBranch branch, RtSnapshot.LiveJourney journey,
                              StopOnLine next, RtSnapshot.LiveJourney.Call call,
                              Vehicle.Confidence confidence,
                              double distance, double fromDist, double toDist) {
        Coordinate point = branch.indexed().extractPoint(distance);
        return new Vehicle(journey.journeyRef(), line.id(), point.y, point.x,
            bearing(branch.indexed(), fromDist, toDist), call.departureStatus(),
            journey.destination(), next.stopName(), call.time(), journey.recordedAt(),
            Vehicle.Source.INTERPOLATED, confidence);
    }

    /**
     * Généralisation directe de l'ancien {@code pickDirection} : les candidates sont les
     * branches contenant l'arrêt imminent (lookup O(1)), départagées par correspondance
     * terminus / destination.
     */
    private LineBranch pickBranch(TrackedLine line, String nextStopKey, String destination) {
        List<LineBranch> candidates = line.branches().stream()
            .filter(branch -> branch.indexOf(nextStopKey) >= 0)
            .toList();
        if (candidates.isEmpty()) {
            return null;
        }
        if (candidates.size() == 1) {
            return candidates.getFirst();
        }
        return candidates.stream()
            .filter(branch -> terminusMatches(branch.terminusName(), destination))
            .findFirst()
            .orElseGet(() -> unresolvedBranch(line, candidates, destination));
    }

    /**
     * Repli quand plusieurs branches contiennent l'arrêt imminent mais qu'aucun terminus ne
     * correspond à la destination (ex. libellés SIRI/GTFS trop éloignés). Signal structurel —
     * pas une ETA — donc admissible au regard de la décision produit : compté et journalisé,
     * jamais masqué ni filtré par un seuil de temps.
     */
    private LineBranch unresolvedBranch(TrackedLine line, List<LineBranch> candidates, String destination) {
        count("mapidf.position.branch.unresolved", line);
        log.debug("[{}] départage de branche non résolu : destination='{}' ne correspond à aucun "
            + "terminus parmi {} — repli sur '{}'", line.id(), destination,
            candidates.stream().map(LineBranch::terminusName).toList(),
            candidates.getFirst().terminusName());
        return candidates.getFirst();
    }

    /**
     * Comparaison insensible à la casse entre un nom de terminus et une destination SIRI : vraie
     * si l'une contient l'autre (l'égalité stricte en est un cas particulier — les libellés SIRI
     * et GTFS diffèrent parfois sur des suffixes), fausse si l'une des deux est nulle.
     */
    static boolean terminusMatches(String terminusName, String destination) {
        if (terminusName == null || destination == null) {
            return false;
        }
        String a = terminusName.toLowerCase(Locale.ROOT);
        String b = destination.toLowerCase(Locale.ROOT);
        return a.contains(b) || b.contains(a);
    }

    static double bearing(LengthIndexedLine indexed, double fromDistance, double toDistance) {
        Coordinate a = indexed.extractPoint(fromDistance);
        Coordinate b = indexed.extractPoint(toDistance);
        double angle = Math.toDegrees(Math.atan2(b.x - a.x, b.y - a.y));
        return (angle + 360) % 360;
    }

    static double clamp(double value, double low, double high) {
        return Math.max(low, Math.min(high, value));
    }

    private static final java.util.regex.Pattern DIGIT_GROUP = java.util.regex.Pattern.compile("\\d+");

    // On extrait le DERNIER groupe de chiffres de la référence : les ids réels (SIRI
    // "STIF:StopPoint:Q:463221:", GTFS "IDFM:463221") n'en ont qu'un, mais un id à préfixe
    // numérique ("IDFM:StopPoint:59:463221") casserait un simple strip de tous les non-chiffres.
    public static String stopKey(String rawRef) {
        if (rawRef == null) {
            return "";
        }
        java.util.regex.Matcher matcher = DIGIT_GROUP.matcher(rawRef);
        String last = "";
        while (matcher.find()) {
            last = matcher.group();
        }
        return last;
    }
}
