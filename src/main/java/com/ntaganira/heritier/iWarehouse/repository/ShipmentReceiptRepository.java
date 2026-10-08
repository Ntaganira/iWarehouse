package com.ntaganira.heritier.iWarehouse.repository;

import com.ntaganira.heritier.iWarehouse.entity.ShipmentReceipt;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ShipmentReceiptRepository extends JpaRepository<ShipmentReceipt, UUID> {

    /** The shipment a receipt came in, if any (for the receipt page). */
    @EntityGraph(attributePaths = "shipment")
    Optional<ShipmentReceipt> findByGoodsReceipt_Id(UUID receiptId);

    /** Receipts already in a shipment. */
    @Query("select r.goodsReceipt.id from ShipmentReceipt r")
    List<UUID> findLinkedReceiptIds();

    /** Receipts already in a shipment other than this one. */
    @Query("select r.goodsReceipt.id from ShipmentReceipt r where r.shipment.id <> :shipmentId")
    List<UUID> findReceiptIdsLinkedElsewhere(@Param("shipmentId") UUID shipmentId);

    /** Rows of (shipment id, receipts) for the shipments list. */
    @Query("select r.shipment.id, count(r) from ShipmentReceipt r where r.shipment.id in :ids group by r.shipment.id")
    List<Object[]> countByShipment(@Param("ids") Collection<UUID> shipmentIds);
}
