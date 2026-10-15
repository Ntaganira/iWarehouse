package com.ntaganira.heritier.iWarehouse.repository;

import com.ntaganira.heritier.iWarehouse.entity.TripDeposit;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.repository
 * - File      : TripDepositRepository.java
 * - Date      : 2026. 10. 15.
 * - User      : Hntaganira
 * - Desc      : Collections banked during trips (BP 7.3 rule 4). Append-only: save and finders only.
 * </pre>
 */
public interface TripDepositRepository extends Repository<TripDeposit, UUID> {

    TripDeposit save(TripDeposit deposit);

    Optional<TripDeposit> findByClientId(UUID clientId);

    List<TripDeposit> findByTripIdOrderByDepositedOnAscCreatedAtAsc(UUID tripId);

    /** Rows of (trip id, amount banked) of some trips: no longer on their drivers' floats. */
    @Query("select d.tripId, coalesce(sum(d.amount), 0) from TripDeposit d where d.tripId in :tripIds group by d.tripId")
    List<Object[]> totalsOfTrips(@Param("tripIds") Collection<UUID> tripIds);
}
