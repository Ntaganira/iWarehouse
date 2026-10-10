package com.ntaganira.heritier.iWarehouse.repository;

import com.ntaganira.heritier.iWarehouse.entity.StockMovement;
import com.ntaganira.heritier.iWarehouse.enums.MovementType;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** Append-only: save and read, no update or delete methods on purpose (INV-04). */
public interface StockMovementRepository extends Repository<StockMovement, UUID> {

    StockMovement save(StockMovement movement);

    List<StockMovement> findByStockUnitIdOrderByMovedAtAsc(UUID stockUnitId);

    /** Per glass, (product id, last movement of the type, m² moved since the day): what each glass sold (RPT-02). */
    @Query("select u.product.id, max(m.movedAt), coalesce(sum(case when m.movedAt >= :since then u.areaM2 else 0 end), 0)"
            + " from StockMovement m, StockUnit u where u.id = m.stockUnitId and m.type = :type group by u.product.id")
    List<Object[]> movedByProduct(@Param("type") MovementType type, @Param("since") LocalDateTime since);
}
