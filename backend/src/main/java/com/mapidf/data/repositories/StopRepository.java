package com.mapidf.data.repositories;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import com.mapidf.data.entity.Stop;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StopRepository extends JpaRepository<Stop, UUID> {

    List<Stop> findByGtfsIdIn(Collection<String> gtfsIds);
}
