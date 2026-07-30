package com.mapidf.controllers.network;

import java.util.List;

/**
 * Tout le réseau statique en un appel : 37 polylignes (8 110 points) et 321 stations
 * dédoublonnées sur le métro réel. Servi depuis le registry, sans requête SQL.
 */
public record NetworkResponse(List<LineDto> lines, List<ShapeDto> shapes, List<StationDto> stations) {

    public record LineDto(String id, String shortName, String color, String mode) {
    }

    /** Une polyligne par branche : le front en fait une feature GeoJSON coloriée par sa ligne. */
    public record ShapeDto(String lineId, short direction, String terminusName, double[][] coordinates) {
    }

    /** Une station physique et les lignes qui la desservent (61 correspondances sur 321). */
    public record StationDto(String id, String name, double lat, double lng, List<String> lineIds) {
    }
}
