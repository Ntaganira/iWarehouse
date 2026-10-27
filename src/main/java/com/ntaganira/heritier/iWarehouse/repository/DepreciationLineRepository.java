package com.ntaganira.heritier.iWarehouse.repository;

import com.ntaganira.heritier.iWarehouse.entity.DepreciationLine;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.repository
 * - File      : DepreciationLineRepository.java
 * - Date      : 2026. 10. 16.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : Depreciation lines (append-only): save and finders. An asset's depreciation is the sum of its lines in posted runs.
 * </pre>
 */
public interface DepreciationLineRepository extends Repository<DepreciationLine, UUID> {

    DepreciationLine save(DepreciationLine line);

    @EntityGraph(attributePaths = {"asset"})
    Page<DepreciationLine> findByRunIdOrderByAsset_NumberAsc(UUID runId, Pageable pageable);

    @EntityGraph(attributePaths = {"asset"})
    List<DepreciationLine> findByRunIdOrderByAsset_NumberAsc(UUID runId);

    /** An asset's lines with their run, newest first: its depreciation tab. */
    @Query(value = "select l, r from DepreciationLine l, DepreciationRun r where r.id = l.runId and l.asset.id = :assetId order by r.period desc, r.runAt desc",
            countQuery = "select count(l) from DepreciationLine l where l.asset.id = :assetId")
    Page<Object[]> findWithRunByAsset(@Param("assetId") UUID assetId, Pageable pageable);

    /** Depreciation in posted runs by asset: (asset id, sum). */
    @Query("select l.asset.id, coalesce(sum(l.amount), 0) from DepreciationLine l, DepreciationRun r where r.id = l.runId"
            + " and r.status = com.ntaganira.heritier.iWarehouse.enums.DepreciationRunStatus.POSTED and l.asset.id in :assetIds group by l.asset.id")
    List<Object[]> postedByAsset(@Param("assetIds") Collection<UUID> assetIds);

    /** Whether an asset has depreciation in a posted run (its cost and life are then fixed). */
    @Query("select count(l) > 0 from DepreciationLine l, DepreciationRun r where r.id = l.runId"
            + " and r.status = com.ntaganira.heritier.iWarehouse.enums.DepreciationRunStatus.POSTED and l.asset.id = :assetId")
    boolean isDepreciated(@Param("assetId") UUID assetId);
}
