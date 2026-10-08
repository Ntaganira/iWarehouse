package com.ntaganira.heritier.iWarehouse.repository;

import com.ntaganira.heritier.iWarehouse.entity.PurchaseOrderLine;
import com.ntaganira.heritier.iWarehouse.enums.PurchaseOrderStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface PurchaseOrderLineRepository extends JpaRepository<PurchaseOrderLine, UUID> {

    /** Lines of several orders, for the totals on the list page. */
    List<PurchaseOrderLine> findByPurchaseOrder_IdIn(Collection<UUID> orderIds);

    /** Lines of open orders for a product: it must stay active while they wait. */
    long countByProduct_IdAndPurchaseOrder_StatusIn(UUID productId, Collection<PurchaseOrderStatus> statuses);
}
