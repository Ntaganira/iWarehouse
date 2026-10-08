package com.ntaganira.heritier.iWarehouse.repository;

import com.ntaganira.heritier.iWarehouse.entity.StockCostEntry;
import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.UUID;

/** Append-only: save and read, no update or delete methods on purpose (PRC-05). */
public interface StockCostEntryRepository extends Repository<StockCostEntry, UUID> {

    StockCostEntry save(StockCostEntry entry);

    List<StockCostEntry> findByStockUnitIdOrderByCreatedAtAscIdAsc(UUID stockUnitId);
}
