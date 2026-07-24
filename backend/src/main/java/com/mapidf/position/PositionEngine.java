package com.mapidf.position;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.mapidf.rt.RtSnapshot;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.linearref.LengthIndexedLine;
import org.springframework.stereotype.Component;

@Component
public class PositionEngine {

    public List<Vehicle> computeAll(LineString line, LineSchedule schedule,
                                    List<RtSnapshot.LiveJourney> journeys, Instant now) {
        LengthIndexedLine indexed = new LengthIndexedLine(line);
        List<Vehicle> out = new ArrayList<>();
        for (RtSnapshot.LiveJourney journey : journeys) {
            Vehicle vehicle = compute(indexed, schedule, journey, now);
            if (vehicle != null) {
                out.add(vehicle);
            }
        }
        return out;
    }

    private Vehicle compute(LengthIndexedLine indexed, LineSchedule schedule,
                            RtSnapshot.LiveJourney journey, Instant now) {
        List<RtSnapshot.LiveJourney.Call> sorted = journey.calls().stream()
            .sorted(Comparator.comparing(RtSnapshot.LiveJourney.Call::time))
            .toList();
        // Arrêt imminent = premier encore à venir ; s'il n'y en a plus, le dernier connu
        // (train tout juste arrivé / fin de données). On n'exclut jamais un train qui a des données.
        RtSnapshot.LiveJourney.Call next = sorted.stream()
            .filter(c -> !c.time().isBefore(now))
            .findFirst()
            .orElse(sorted.getLast());
        // Dernier arrêt déjà passé, s'il figure dans le flux (≈ 1 course sur 3 en a un).
        RtSnapshot.LiveJourney.Call prev = sorted.stream()
            .filter(c -> c.time().isBefore(now))
            .reduce((a, b) -> b)
            .orElse(null);

        DirectionSchedule direction = pickDirection(schedule, journey.destination(), stopKey(next.stopRef()));
        if (direction == null) {
            return null;
        }
        List<StopOnLine> stops = direction.stops();
        int nextIdx = indexOfStop(stops, stopKey(next.stopRef()));
        if (nextIdx < 0) {
            return null;
        }
        StopOnLine to = stops.get(nextIdx);

        if (nextIdx == 0) {
            // Prochain arrêt = tête de ligne dans ce sens → placé à l'origine.
            StopOnLine after = stops.size() > 1 ? stops.get(1) : to;
            return vehicleAt(indexed, journey, to, next,
                to.distanceAlongLine(), to.distanceAlongLine(), after.distanceAlongLine());
        }

        // Origine du segment courant : le dernier arrêt SIRI passé s'il est en amont dans ce sens
        // (interpolation aux VRAIES heures estimées → capte le temps à quai) ; sinon l'arrêt
        // précédent du tracé, dont on estime l'heure de départ via l'horaire théorique.
        int prevIdx = prev == null ? -1 : indexOfStop(stops, stopKey(prev.stopRef()));
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
        return vehicleAt(indexed, journey, to, next, distance, fromDist, to.distanceAlongLine());
    }

    private Vehicle vehicleAt(LengthIndexedLine indexed, RtSnapshot.LiveJourney journey,
                              StopOnLine next, RtSnapshot.LiveJourney.Call call,
                              double distance, double fromDist, double toDist) {
        Coordinate point = indexed.extractPoint(distance);
        return new Vehicle(journey.journeyRef(), point.y, point.x, bearing(indexed, fromDist, toDist),
            call.departureStatus(), journey.destination(), next.stopName(), call.time(),
            Vehicle.Source.INTERPOLATED);
    }

    private DirectionSchedule pickDirection(LineSchedule schedule, String destination, String key) {
        List<DirectionSchedule> candidates = schedule.directions().stream()
            .filter(d -> indexOfStop(d.stops(), key) >= 0)
            .toList();
        if (candidates.isEmpty()) {
            return null;
        }
        if (candidates.size() == 1) {
            return candidates.getFirst();
        }
        return candidates.stream()
            .filter(d -> terminusMatches(d.terminusName(), destination))
            .findFirst()
            .orElse(candidates.getFirst());
    }

    private static boolean terminusMatches(String terminus, String destination) {
        if (terminus == null || destination == null) {
            return false;
        }
        String t = terminus.toLowerCase();
        String d = destination.toLowerCase();
        return t.equals(d) || t.contains(d) || d.contains(t);
    }

    private static int indexOfStop(List<StopOnLine> stops, String key) {
        for (int i = 0; i < stops.size(); i++) {
            if (stops.get(i).stopKey().equals(key)) {
                return i;
            }
        }
        return -1;
    }

    private double bearing(LengthIndexedLine indexed, double fromDistance, double toDistance) {
        Coordinate a = indexed.extractPoint(fromDistance);
        Coordinate b = indexed.extractPoint(toDistance);
        double angle = Math.toDegrees(Math.atan2(b.x - a.x, b.y - a.y));
        return (angle + 360) % 360;
    }

    private static double clamp(double value, double low, double high) {
        return Math.max(low, Math.min(high, value));
    }

    public static String stopKey(String rawRef) {
        return rawRef == null ? "" : rawRef.replaceAll("\\D", "");
    }
}
