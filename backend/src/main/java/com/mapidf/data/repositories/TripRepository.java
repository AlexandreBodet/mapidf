package com.mapidf.data.repositories;

import java.util.UUID;

import com.mapidf.data.entity.Trip;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TripRepository extends JpaRepository<Trip, UUID> {
}
