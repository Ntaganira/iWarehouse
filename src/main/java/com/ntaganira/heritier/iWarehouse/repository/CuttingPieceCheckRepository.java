package com.ntaganira.heritier.iWarehouse.repository;

import com.ntaganira.heritier.iWarehouse.entity.CuttingPieceCheck;
import org.springframework.data.repository.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.repository
 * - File      : CuttingPieceCheckRepository.java
 * - Date      : 2026. 10. 15.
 * - User      : Hntaganira
 * - Desc      : Cut pieces measured and accepted or rejected (IMP 10 step 3). Append-only: save and finders only.
 * </pre>
 */
public interface CuttingPieceCheckRepository extends Repository<CuttingPieceCheck, UUID> {

    CuttingPieceCheck save(CuttingPieceCheck check);

    List<CuttingPieceCheck> findByCuttingJobIdOrderByUnitCode(UUID cuttingJobId);

    List<CuttingPieceCheck> findByStockUnitIdIn(Collection<UUID> stockUnitIds);
}
