package com.ntaganira.heritier.iWarehouse.repository;

import com.ntaganira.heritier.iWarehouse.entity.CrateBatch;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface CrateBatchRepository extends JpaRepository<CrateBatch, UUID> {

    /** Rows of (receipt id, crates, good sheets, broken sheets) for the receipts list. */
    @Query("select c.goodsReceipt.id, count(c), sum(c.sheets), sum(c.broken) from CrateBatch c"
            + " where c.goodsReceipt.id in :ids group by c.goodsReceipt.id")
    List<Object[]> totalsByReceipt(@Param("ids") Collection<UUID> receiptIds);

    /** Crates of some receipts with their product and receipt, by receipt then crate (a shipment's crates). */
    @EntityGraph(attributePaths = {"product", "goodsReceipt"})
    @Query("select c from CrateBatch c where c.goodsReceipt.id in :ids order by c.goodsReceipt.number, c.createdAt, c.batchNo")
    List<CrateBatch> findByReceipts(@Param("ids") Collection<UUID> receiptIds);
}
