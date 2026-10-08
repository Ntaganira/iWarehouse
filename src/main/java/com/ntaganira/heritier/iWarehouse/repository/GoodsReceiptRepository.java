package com.ntaganira.heritier.iWarehouse.repository;

import com.ntaganira.heritier.iWarehouse.entity.GoodsReceipt;
import com.ntaganira.heritier.iWarehouse.enums.GoodsReceiptStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface GoodsReceiptRepository extends JpaRepository<GoodsReceipt, UUID>, JpaSpecificationExecutor<GoodsReceipt> {

    @EntityGraph(attributePaths = {"purchaseOrder", "purchaseOrder.supplier"})
    Page<GoodsReceipt> findAll(Specification<GoodsReceipt> spec, Pageable pageable);

    @EntityGraph(attributePaths = {"purchaseOrder", "purchaseOrder.supplier", "crates", "crates.product",
            "crates.location", "crates.poLine"})
    Optional<GoodsReceipt> findWithCratesById(UUID id);

    @EntityGraph(attributePaths = "crates")
    List<GoodsReceipt> findByPurchaseOrder_IdOrderByCreatedAtDesc(UUID orderId);

    /** Receipts in a status with their order and supplier, newest first (shipment form). */
    @EntityGraph(attributePaths = {"purchaseOrder", "purchaseOrder.supplier"})
    List<GoodsReceipt> findByStatusOrderByReceivedDateDescNumberDesc(GoodsReceiptStatus status);

    long countByPurchaseOrder_IdAndStatus(UUID orderId, GoodsReceiptStatus status);

    @Query("select r.purchaseOrder.id from GoodsReceipt r where r.id = :id")
    Optional<UUID> findOrderId(@Param("id") UUID id);

    /** Products on a receipt, so posting can lock them before reading stock (moving average cost). */
    @Query("select distinct c.product.id from CrateBatch c where c.goodsReceipt.id = :id")
    List<UUID> findProductIds(@Param("id") UUID id);
}
