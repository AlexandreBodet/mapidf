package com.mapidf.data.repositories;

import java.util.List;
import java.util.UUID;

import com.mapidf.data.entity.Branch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface BranchRepository extends JpaRepository<Branch, UUID> {

    /**
     * JOIN FETCH explicite : sans lui, la réhydratation du registry ferait un N+1
     * (une requête par branche pour charger sa route).
     */
    @Query("""
        SELECT b FROM Branch b
        JOIN FETCH b.route r
        ORDER BY r.gtfsId, b.direction, b.gtfsShapeId
        """)
    List<Branch> findAllWithRoute();
}
