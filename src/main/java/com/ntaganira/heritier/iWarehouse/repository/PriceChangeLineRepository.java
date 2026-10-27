package com.ntaganira.heritier.iWarehouse.repository;

import com.ntaganira.heritier.iWarehouse.entity.PriceChangeLine;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.repository
 * - File      : PriceChangeLineRepository.java
 * - Date      : 2026. 10. 15.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : The lines of a price change (append-only): saved once, then only read.
 * </pre>
 */
public interface PriceChangeLineRepository extends Repository<PriceChangeLine, UUID> {

    PriceChangeLine save(PriceChangeLine line);

    @EntityGraph(attributePaths = {"product", "service"})
    List<PriceChangeLine> findByChangeIdOrderByLineNo(UUID changeId);
}
