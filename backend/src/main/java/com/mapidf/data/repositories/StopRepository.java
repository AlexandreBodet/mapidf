package com.mapidf.data.repositories;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.mapidf.data.entity.Stop;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StopRepository extends JpaRepository<Stop, UUID> {

    List<Stop> findByParentStation(String parentStation);

    Optional<Stop> findByGtfsId(String gtfsId);

    @Query("""
        SELECT DISTINCT s FROM StopTime st
        JOIN st.stop s
        WHERE st.trip.route.gtfsId = :routeId
        ORDER BY s.gtfsId
        """)
    List<Stop> findDistinctStopsByRouteGtfsId(@Param("routeId") String routeId);
}
