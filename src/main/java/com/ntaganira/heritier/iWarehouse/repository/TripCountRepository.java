package com.ntaganira.heritier.iWarehouse.repository;

import com.ntaganira.heritier.iWarehouse.entity.TripCount;
import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.repository
 * - File      : TripCountRepository.java
 * - Date      : 2026. 10. 15.
 * - User      : Hntaganira
 * - Desc      : Counts of van stock on the road (SDP 6). Append-only: save and finders only.
 * </pre>
 */
public interface TripCountRepository extends Repository<TripCount, UUID> {

    TripCount save(TripCount count);

    Optional<TripCount> findByClientId(UUID clientId);

    Optional<TripCount> findById(UUID id);

    List<TripCount> findByTripIdOrderByCountedAtDesc(UUID tripId);
}
