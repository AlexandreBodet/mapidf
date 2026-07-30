package com.mapidf.controllers.network;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.mapidf.controllers.network.NetworkResponse.LineDto;
import com.mapidf.controllers.network.NetworkResponse.ShapeDto;
import com.mapidf.controllers.network.NetworkResponse.StationDto;
import com.mapidf.network.LineBranch;
import com.mapidf.network.LineRegistry;
import com.mapidf.network.NetworkSnapshot;
import com.mapidf.network.TrackedLine;
import lombok.AllArgsConstructor;
import org.locationtech.jts.geom.Coordinate;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@AllArgsConstructor
public class NetworkController {

    private final LineRegistry registry;

    @GetMapping("/network")
    public ResponseEntity<NetworkResponse> network() {
        NetworkSnapshot snapshot = registry.current();

        List<LineDto> lines = snapshot.lines().stream()
            .map(line -> new LineDto(line.id(), line.shortName(), line.color(), line.mode()))
            .toList();

        List<ShapeDto> shapes = new ArrayList<>();
        for (TrackedLine line : snapshot.lines()) {
            for (LineBranch branch : line.branches()) {
                shapes.add(new ShapeDto(line.id(), branch.direction(), branch.terminusName(),
                    toCoordinates(branch)));
            }
        }

        List<StationDto> stations = snapshot.stations().stream()
            .map(station -> new StationDto(station.id(), station.name(),
                station.lat(), station.lng(), station.lineIds()))
            .toList();

        // Statique entre deux rechargements GTFS (un par jour) : on laisse le navigateur cacher
        // plutôt que de resérialiser 8 110 points à chaque onglet ouvert.
        //
        // MAIS jamais quand le registry est vide. Après la migration V4 la base est vide par
        // construction, hydrateOnStartup publie alors un NetworkSnapshot.empty() sans lever, et
        // cette méthode répond 200 avec un réseau vide. Le cacher 10 minutes en public
        // survivrait au retour à la normale du backend, et tout proxy intermédiaire propagerait
        // la carte vide à tous les clients — d'autant que useNetwork ne refetche jamais.
        boolean ready = !snapshot.lines().isEmpty();
        return ResponseEntity.ok()
            .cacheControl(ready
                ? CacheControl.maxAge(Duration.ofMinutes(10)).cachePublic()
                : CacheControl.noStore())
            .body(new NetworkResponse(lines, shapes, stations));
    }

    private static double[][] toCoordinates(LineBranch branch) {
        Coordinate[] source = branch.geom().getCoordinates();
        double[][] out = new double[source.length][];
        for (int i = 0; i < source.length; i++) {
            out[i] = new double[]{source[i].x, source[i].y};
        }
        return out;
    }
}
