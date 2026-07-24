package com.mapidf.position;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
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
        String key = stopKey(journey.nextStopRef());
        DirectionSchedule direction = pickDirection(schedule, journey.destination(), key);
        if (direction == null) {
            return null;
        }
        List<StopOnLine> stops = direction.stops();
        int index = indexOfStop(stops, key);
        if (index < 0) {
            return null;
        }
        StopOnLine next = stops.get(index);
        long etaDeltaSec = Duration.between(now, journey.expectedTime()).getSeconds();

        double distance;
        double bearing;
        if (index == 0) {
            distance = next.distanceAlongLine();
            StopOnLine after = stops.size() > 1 ? stops.get(1) : next;
            bearing = bearing(indexed, next.distanceAlongLine(), after.distanceAlongLine());
        } else {
            // SIRI ne donne que l'ETA du prochain arrêt reporté ; il est souvent à
            // plusieurs inter-stations. On remonte les segments théoriques depuis
            // `next` en consommant l'ETA, pour situer le véhicule dans le bon segment
            // (sinon il resterait collé à l'arrêt précédent tant que ETA > 1 segment).
            long remaining = Math.max(0, etaDeltaSec);
            int target = index;
            while (target > 1) {
                int segDur = stops.get(target).scheduledSec() - stops.get(target - 1).scheduledSec();
                if (segDur <= 0 || remaining <= segDur) {
                    break;
                }
                remaining -= segDur;
                target--;
            }
            StopOnLine to = stops.get(target);
            StopOnLine from = stops.get(target - 1);
            int segmentSec = to.scheduledSec() - from.scheduledSec();
            double fraction = segmentSec > 0 ? clamp(1.0 - (double) remaining / segmentSec, 0.0, 1.0) : 1.0;
            distance = from.distanceAlongLine()
                + fraction * (to.distanceAlongLine() - from.distanceAlongLine());
            bearing = bearing(indexed, from.distanceAlongLine(), to.distanceAlongLine());
        }

        Coordinate point = indexed.extractPoint(distance);
        return new Vehicle(journey.journeyRef(), point.y, point.x, bearing, journey.departureStatus(),
            journey.destination(), next.stopName(), journey.expectedTime(), Vehicle.Source.INTERPOLATED);
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
