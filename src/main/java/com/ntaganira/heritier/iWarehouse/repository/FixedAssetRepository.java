package com.ntaganira.heritier.iWarehouse.repository;

import com.ntaganira.heritier.iWarehouse.entity.FixedAsset;
import com.ntaganira.heritier.iWarehouse.enums.FixedAssetStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.repository
 * - File      : FixedAssetRepository.java
 * - Date      : 2026. 10. 16.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : Fixed assets (C.08): pages, one locked, those in use, those of an expense bill.
 * </pre>
 */
public interface FixedAssetRepository extends JpaRepository<FixedAsset, UUID>, JpaSpecificationExecutor<FixedAsset> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from FixedAsset a where a.id = :id")
    Optional<FixedAsset> lockById(@Param("id") UUID id);

    List<FixedAsset> findByStatusOrderByNumberAsc(FixedAssetStatus status);

    /** In use, locked: a month's run reads and depreciates them. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from FixedAsset a where a.status = com.ntaganira.heritier.iWarehouse.enums.FixedAssetStatus.ACTIVE order by a.number")
    List<FixedAsset> lockActive();

    List<FixedAsset> findByExpenseBillLineIdIn(Collection<UUID> lineIds);

    long countByStatus(FixedAssetStatus status);
}
