package com.ntaganira.heritier.iWarehouse.repository;

import com.ntaganira.heritier.iWarehouse.entity.ShipmentCost;
import com.ntaganira.heritier.iWarehouse.enums.ShipmentCostStatus;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface ShipmentCostRepository extends JpaRepository<ShipmentCost, UUID> {

    /** Rows of (shipment id, cost lines) in a status, for the shipments list. */
    @Query("select c.shipment.id, count(c) from ShipmentCost c where c.shipment.id in :ids and c.status = :status"
            + " group by c.shipment.id")
    List<Object[]> countByShipment(@Param("ids") Collection<UUID> shipmentIds, @Param("status") ShipmentCostStatus status);

    /** Import bills posted with their date in a period, with their shipment (landed cost and demurrage against budget, T.13, C.12). */
    @EntityGraph(attributePaths = {"shipment"})
    @Query("select c from ShipmentCost c where c.status = com.ntaganira.heritier.iWarehouse.enums.ShipmentCostStatus.POSTED"
            + " and c.invoiceDate between :from and :to order by c.invoiceDate")
    List<ShipmentCost> postedIn(@Param("from") java.time.LocalDate from, @Param("to") java.time.LocalDate to);
}
