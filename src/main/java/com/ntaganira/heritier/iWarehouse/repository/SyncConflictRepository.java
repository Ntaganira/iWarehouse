package com.ntaganira.heritier.iWarehouse.repository;

import com.ntaganira.heritier.iWarehouse.entity.SyncConflict;
import com.ntaganira.heritier.iWarehouse.enums.SyncConflictStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.repository
 * - File      : SyncConflictRepository.java
 * - Date      : 2026. 10. 10.
 * - User      : Hntaganira
 * - Desc      : Mobile sales in conflict (SYNC-05).
 * </pre>
 */
public interface SyncConflictRepository extends JpaRepository<SyncConflict, UUID>, JpaSpecificationExecutor<SyncConflict> {

    @EntityGraph(attributePaths = {"trip", "trip.vehicle", "device"})
    Page<SyncConflict> findAll(Specification<SyncConflict> spec, Pageable pageable);

    @EntityGraph(attributePaths = {"trip", "trip.vehicle", "trip.driver", "trip.driver.user", "device"})
    Optional<SyncConflict> findDetailedById(UUID id);

    Optional<SyncConflict> findByClientId(UUID clientId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from SyncConflict c where c.id = :id")
    Optional<SyncConflict> lockById(@Param("id") UUID id);

    long countByStatus(SyncConflictStatus status);

    long countByTrip_IdAndStatus(UUID tripId, SyncConflictStatus status);
}
