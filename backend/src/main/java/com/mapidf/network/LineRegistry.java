package com.mapidf.network;

import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import com.mapidf.data.enums.ErrorCode;
import com.mapidf.exceptions.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Source unique de vérité du réseau suivi. L'état est publié en bloc par un
 * {@link AtomicReference} : aucune requête ne voit un réseau à moitié rebâti.
 */
@Component
public class LineRegistry {

    private final AtomicReference<NetworkSnapshot> snapshot =
        new AtomicReference<>(NetworkSnapshot.empty());

    public NetworkSnapshot current() {
        return snapshot.get();
    }

    public void publish(NetworkSnapshot next) {
        snapshot.set(next);
    }

    /**
     * Surcharge sur un instantané déjà lu : un appelant qui a besoin du réseau ET de la station
     * doit les tirer du MÊME instantané, sinon un refresh GTFS glissé entre les deux lectures lui
     * ferait bâtir une réponse sur une station absente du réseau qu'il utilise.
     */
    public static Station requireStation(NetworkSnapshot network, String id) {
        Station station = network.stationsById().get(id);
        if (station == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, ErrorCode.STATION_NOT_FOUND);
        }
        return station;
    }

    /** LineRef SIRI des lignes suivies : sert à filtrer le flux global au fil de l'eau. */
    public Set<String> trackedSiriLineRefs() {
        return current().linesBySiriRef().keySet();
    }
}
