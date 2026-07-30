package com.mapidf.configurations.properties;

import java.util.List;

import com.mapidf.data.enums.TransportMode;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Périmètre du réseau suivi. {@code modes} sélectionne les {@code route_type} GTFS à charger,
 * {@code exclude} permet d'écarter une {@code route_id} précise (ligne au référentiel atypique).
 */
@ConfigurationProperties(prefix = "app.network")
public record NetworkProperties(
    List<TransportMode> modes,
    List<String> exclude
) {
    public NetworkProperties {
        modes = modes == null ? List.of() : List.copyOf(modes);
        exclude = exclude == null ? List.of() : List.copyOf(exclude);
    }

    public boolean tracks(TransportMode mode) {
        return modes.contains(mode);
    }

    public boolean isExcluded(String gtfsRouteId) {
        return exclude.contains(gtfsRouteId);
    }
}
