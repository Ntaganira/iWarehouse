package com.ntaganira.heritier.iWarehouse.repository;

import com.ntaganira.heritier.iWarehouse.entity.ShipmentAllocation;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** Append-only: save and read, no update or delete methods on purpose (PRC-04). */
public interface ShipmentAllocationRepository extends Repository<ShipmentAllocation, UUID> {

    ShipmentAllocation save(ShipmentAllocation allocation);

    List<ShipmentAllocation> findByShipmentIdOrderByPostingNoAsc(UUID shipmentId);

    List<ShipmentAllocation> findByCrateBatchIdIn(Collection<UUID> crateIds);

    /** Rows of (shipment id, RWF allocated) for the shipments list. */
    @Query("select a.shipmentId, sum(a.amount) from ShipmentAllocation a where a.shipmentId in :ids group by a.shipmentId")
    List<Object[]> totalsByShipment(@Param("ids") Collection<UUID> shipmentIds);
}
