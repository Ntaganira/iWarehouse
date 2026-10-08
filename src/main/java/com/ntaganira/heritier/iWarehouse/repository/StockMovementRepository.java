package com.ntaganira.heritier.iWarehouse.repository;

import com.ntaganira.heritier.iWarehouse.entity.StockMovement;
import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.UUID;

/** Append-only: save and read, no update or delete methods on purpose (INV-04). */
public interface StockMovementRepository extends Repository<StockMovement, UUID> {

    StockMovement save(StockMovement movement);

    List<StockMovement> findByStockUnitIdOrderByMovedAtAsc(UUID stockUnitId);
}
