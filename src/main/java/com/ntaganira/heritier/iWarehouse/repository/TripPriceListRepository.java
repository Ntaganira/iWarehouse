package com.ntaganira.heritier.iWarehouse.repository;

import com.ntaganira.heritier.iWarehouse.entity.TripPriceList;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.repository
 * - File      : TripPriceListRepository.java
 * - Date      : 2026. 10. 10.
 * - User      : Hntaganira
 * - Desc      : The price lists frozen for a trip (MPOS-04). Append-only: save and finders.
 * </pre>
 */
public interface TripPriceListRepository extends JpaRepository<TripPriceList, TripPriceList.Key> {

    List<TripPriceList> findByTripId(UUID tripId);

    boolean existsByTripId(UUID tripId);
}
