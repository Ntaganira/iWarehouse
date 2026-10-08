package com.ntaganira.heritier.iWarehouse.repository;

import com.ntaganira.heritier.iWarehouse.entity.StockAdjustmentLine;
import com.ntaganira.heritier.iWarehouse.enums.AdjustmentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface StockAdjustmentLineRepository extends JpaRepository<StockAdjustmentLine, UUID> {

    /** Rows of (unit id, adjustment number) for units on adjustments in a status: the units they hold (INV-05). */
    @Query("select l.stockUnitId, a.number from StockAdjustmentLine l join l.adjustment a"
            + " where a.status = :status and l.stockUnitId in :unitIds")
    List<Object[]> findHolds(@Param("status") AdjustmentStatus status, @Param("unitIds") Collection<UUID> unitIds);
}
