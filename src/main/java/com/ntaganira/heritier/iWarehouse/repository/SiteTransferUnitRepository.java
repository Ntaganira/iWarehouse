package com.ntaganira.heritier.iWarehouse.repository;

import com.ntaganira.heritier.iWarehouse.entity.SiteTransferUnit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.repository
 * - File      : SiteTransferUnitRepository.java
 * - Date      : 2026. 10. 15.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : The units sent on transfers between sites, and whether each arrived.
 * </pre>
 */
public interface SiteTransferUnitRepository extends JpaRepository<SiteTransferUnit, UUID> {

    List<SiteTransferUnit> findByTransferIdOrderByUnitCode(UUID transferId);

    Optional<SiteTransferUnit> findByTransferIdAndStockUnitId(UUID transferId, UUID stockUnitId);

    /** Units sent in a period, and of them those received (the monthly reconciliation). */
    @Query("select count(u), sum(case when u.receivedAt is not null then 1 else 0 end) from SiteTransferUnit u"
            + " where u.dispatchedAt between :from and :to")
    List<Object[]> dispatchedBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** Of the units sent in a period, those still in transit (their transfer not received yet). */
    @Query("select count(u) from SiteTransferUnit u, SiteTransfer t where t.id = u.transferId and u.receivedAt is null"
            + " and t.status = com.ntaganira.heritier.iWarehouse.enums.SiteTransferStatus.IN_TRANSIT and u.dispatchedAt between :from and :to")
    long inTransitBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);
}
