package com.mapidf.data.repositories;

import java.util.Optional;
import java.util.UUID;

import com.mapidf.data.entity.Route;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RouteRepository extends JpaRepository<Route, UUID> {
    Optional<Route> findByGtfsId(String gtfsId);
}
