package com.mapidf.data.repositories;

import java.util.List;
import java.util.UUID;

import com.mapidf.data.entity.StopTime;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface StopTimeRepository extends JpaRepository<StopTime, UUID> {

    /**
     * Tout ce qu'il faut au registry, en UNE requête : les arrêts de chaque branche, dans
     * l'ordre de desserte, avec branche et arrêt déjà chargés. Le volume est petit par
     * construction (915 lignes sur tout le métro) puisque seuls les parcours représentatifs
     * sont persistés.
     */
    @Query("""
        SELECT st FROM StopTime st
        JOIN FETCH st.branch b
        JOIN FETCH st.stop s
        ORDER BY b.id, st.stopSequence
        """)
    List<StopTime> findAllForRegistry();

    /** Arrêts d'une branche donnée, pour les assertions d'intégration. */
    @Query("""
        SELECT st FROM StopTime st
        JOIN FETCH st.branch b
        JOIN FETCH st.stop s
        WHERE b.gtfsShapeId = :shapeId
        ORDER BY st.stopSequence
        """)
    List<StopTime> findByShapeId(String shapeId);
}
