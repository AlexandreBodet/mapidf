package com.mapidf.data.repositories;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.mapidf.data.entity.Stop;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StopRepository extends JpaRepository<Stop, UUID> {

    List<Stop> findByParentStation(String parentStation);

    Optional<Stop> findByGtfsId(String gtfsId);

    List<Stop> findByGtfsIdIn(Collection<String> gtfsIds);
}
