package com.ntaganira.heritier.iWarehouse.repository;

import com.ntaganira.heritier.iWarehouse.entity.EbmItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.repository
 * - File      : EbmItemRepository.java
 * - Date      : 2026. 10. 10.
 * - User      : Hntaganira
 * - Desc      : Glass and processing registered with the VSDC (or the simulator), and the serial of new item codes.
 * </pre>
 */
public interface EbmItemRepository extends JpaRepository<EbmItem, UUID> {

    Optional<EbmItem> findByProductIdAndSimulated(UUID productId, boolean simulated);

    Optional<EbmItem> findByServiceIdAndSimulated(UUID serviceId, boolean simulated);

    /** Its registrations in either mode: the item code stays the same. */
    List<EbmItem> findByProductId(UUID productId);

    List<EbmItem> findByServiceId(UUID serviceId);

    @Query(value = "select nextval('ebm_item_serial')", nativeQuery = true)
    long nextSerial();
}
