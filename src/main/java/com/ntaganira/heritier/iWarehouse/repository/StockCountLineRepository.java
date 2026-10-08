package com.ntaganira.heritier.iWarehouse.repository;

import com.ntaganira.heritier.iWarehouse.entity.StockCountLine;
import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.repository
 * - File      : StockCountLineRepository.java
 * - Date      : 2026. 10. 08.
 * - User      : Hntaganira
 * - Desc      : The result lines of closed stock counts (INV-08). Append-only: save and finders only.
 * </pre>
 */
public interface StockCountLineRepository extends Repository<StockCountLine, UUID> {

    StockCountLine save(StockCountLine line);

    List<StockCountLine> findByCountIdOrderByLineNo(UUID countId);
}
