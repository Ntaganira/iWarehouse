package com.ntaganira.heritier.iWarehouse.repository;

import com.ntaganira.heritier.iWarehouse.entity.FxRevaluationLine;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.repository
 * - File      : FxRevaluationLineRepository.java
 * - Date      : 2026. 10. 09.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : What a revaluation revalued (ACC-08). Append-only: save and finders only.
 * </pre>
 */
public interface FxRevaluationLineRepository extends Repository<FxRevaluationLine, UUID> {

    FxRevaluationLine save(FxRevaluationLine line);

    @EntityGraph(attributePaths = {"account", "supplier"})
    List<FxRevaluationLine> findByRevaluationIdOrderByLineNo(UUID revaluationId);
}
