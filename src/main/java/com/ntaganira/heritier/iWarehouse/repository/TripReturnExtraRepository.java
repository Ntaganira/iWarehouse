package com.ntaganira.heritier.iWarehouse.repository;

import com.ntaganira.heritier.iWarehouse.entity.TripReturnExtra;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.repository
 * - File      : TripReturnExtraRepository.java
 * - Date      : 2026. 10. 11.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : Labels scanned at a trip's return that are no unit expected back (FLT-10).
 * </pre>
 */
public interface TripReturnExtraRepository extends JpaRepository<TripReturnExtra, UUID> {

    List<TripReturnExtra> findByTrip_IdOrderByScannedAtAscCodeAsc(UUID tripId);

    Optional<TripReturnExtra> findByTrip_IdAndCode(UUID tripId, String code);
}
