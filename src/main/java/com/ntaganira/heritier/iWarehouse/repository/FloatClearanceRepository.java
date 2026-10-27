package com.ntaganira.heritier.iWarehouse.repository;

import com.ntaganira.heritier.iWarehouse.entity.FloatClearance;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.repository
 * - File      : FloatClearanceRepository.java
 * - Date      : 2026. 10. 11.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : Driver floats cleared by the accountant (ACC-06, ACC-07).
 * </pre>
 */
public interface FloatClearanceRepository extends JpaRepository<FloatClearance, UUID> {

    @EntityGraph(attributePaths = {"trip", "trip.vehicle", "driver", "driver.user"})
    Page<FloatClearance> findAllByOrderByClearedAtDesc(Pageable pageable);

    @EntityGraph(attributePaths = {"trip", "trip.vehicle", "driver", "driver.user"})
    Optional<FloatClearance> findDetailedById(UUID id);

    Optional<FloatClearance> findByTrip_Id(UUID tripId);

    boolean existsByTrip_Id(UUID tripId);

    /** Clearances of some trips (the fleet reports). */
    @Query("select f from FloatClearance f where f.trip.id in :tripIds")
    List<FloatClearance> findByTripIds(@Param("tripIds") Collection<UUID> tripIds);
}
