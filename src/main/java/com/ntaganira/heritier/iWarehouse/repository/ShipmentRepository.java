package com.ntaganira.heritier.iWarehouse.repository;

import com.ntaganira.heritier.iWarehouse.entity.Shipment;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface ShipmentRepository extends JpaRepository<Shipment, UUID>, JpaSpecificationExecutor<Shipment> {

    /** A shipment with its receipts (order, supplier) and cost lines (paid to). */
    @EntityGraph(attributePaths = {"receipts", "receipts.goodsReceipt", "receipts.goodsReceipt.purchaseOrder",
            "receipts.goodsReceipt.purchaseOrder.supplier", "costs", "costs.supplier"})
    Optional<Shipment> findDetailedById(UUID id);

    /** Posting takes this lock first, so two postings of one shipment run one at a time. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Shipment s where s.id = :id")
    Optional<Shipment> lockById(@Param("id") UUID id);
}
