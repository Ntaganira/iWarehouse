package com.ntaganira.heritier.iWarehouse.repository;

import com.ntaganira.heritier.iWarehouse.entity.SalesDelivery;
import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.repository
 * - File      : SalesDeliveryRepository.java
 * - Date      : 2026. 10. 09.
 * - User      : Hntaganira
 * - Desc      : Pieces of custom sizes handed over (SRS 5.3). Append-only: save and finders only.
 * </pre>
 */
public interface SalesDeliveryRepository extends Repository<SalesDelivery, UUID> {

    SalesDelivery save(SalesDelivery delivery);

    List<SalesDelivery> findByInvoiceIdOrderByDeliveredAtAscUnitCodeAsc(UUID invoiceId);
}
