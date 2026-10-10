package com.ntaganira.heritier.iWarehouse.repository;

import com.ntaganira.heritier.iWarehouse.entity.TripPrice;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.repository
 * - File      : TripPriceRepository.java
 * - Date      : 2026. 10. 10.
 * - User      : Hntaganira
 * - Desc      : The prices frozen for a trip (MPOS-04). Append-only: save and finders.
 * </pre>
 */
public interface TripPriceRepository extends JpaRepository<TripPrice, TripPrice.Key> {

    List<TripPrice> findByTripId(UUID tripId);

    boolean existsByTripId(UUID tripId);
}
