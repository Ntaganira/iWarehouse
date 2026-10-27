package com.ntaganira.heritier.iWarehouse.repository;

import com.ntaganira.heritier.iWarehouse.entity.SiteTransferLine;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.repository
 * - File      : SiteTransferLineRepository.java
 * - Date      : 2026. 10. 15.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : What transfers between sites ask for: saved with the request, then only read.
 * </pre>
 */
public interface SiteTransferLineRepository extends Repository<SiteTransferLine, UUID> {

    SiteTransferLine save(SiteTransferLine line);

    @EntityGraph(attributePaths = {"product"})
    List<SiteTransferLine> findByTransferIdOrderByLineNo(UUID transferId);
}
