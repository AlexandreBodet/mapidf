package com.mapidf.controllers.stations;

import java.util.List;

import com.mapidf.controllers.network.NetworkResponse;

/** Résultats d'une recherche de station (UX-5a) ; réutilise le DTO déjà servi par `/network`. */
public record StationSearchResponse(List<NetworkResponse.StationDto> results) {
}
