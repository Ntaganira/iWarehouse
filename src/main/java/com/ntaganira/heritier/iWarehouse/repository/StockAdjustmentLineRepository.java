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

    /** Write-offs of a cause on adjustments posted in a period, with their adjustment (the damage register). */
    @Query("select l from StockAdjustmentLine l join fetch l.adjustment a where l.kind = com.ntaganira.heritier.iWarehouse.enums.AdjustmentKind.WRITE_OFF"
            + " and l.cause = :cause and a.status = com.ntaganira.heritier.iWarehouse.enums.AdjustmentStatus.POSTED"
            + " and a.postedAt between :from and :to order by a.postedAt, a.number, l.lineNo")
    List<StockAdjustmentLine> findPostedWriteOffs(@Param("cause") com.ntaganira.heritier.iWarehouse.enums.WriteOffCause cause,
                                                  @Param("from") java.time.LocalDateTime from, @Param("to") java.time.LocalDateTime to);
}
