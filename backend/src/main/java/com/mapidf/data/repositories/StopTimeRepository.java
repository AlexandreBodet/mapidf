package com.mapidf.data.repositories;

import java.util.List;
import java.util.UUID;

import com.mapidf.data.entity.StopTime;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StopTimeRepository extends JpaRepository<StopTime, UUID> {

    @Query("""
        SELECT st FROM StopTime st
        JOIN FETCH st.trip t
        JOIN FETCH st.stop s
        WHERE t.route.gtfsId = :routeId
        ORDER BY t.gtfsId, st.stopSequence
        """)
    List<StopTime> findScheduleByRouteGtfsId(@Param("routeId") String routeId);
}
