package com.ntaganira.heritier.iWarehouse.repository;

import com.ntaganira.heritier.iWarehouse.entity.SupplierInvoiceLine;
import org.springframework.data.repository.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.repository
 * - File      : SupplierInvoiceLineRepository.java
 * - Date      : 2026. 10. 09.
 * - User      : Hntaganira
 * - Desc      : The receipts supplier invoices bill (ACC-09). Append-only: save and finders only.
 * </pre>
 */
public interface SupplierInvoiceLineRepository extends Repository<SupplierInvoiceLine, UUID> {

    SupplierInvoiceLine save(SupplierInvoiceLine line);

    List<SupplierInvoiceLine> findByInvoiceIdOrderByLineNo(UUID invoiceId);

    /** The lines that billed these receipts already. */
    List<SupplierInvoiceLine> findByGoodsReceiptIdIn(Collection<UUID> goodsReceiptIds);
}
