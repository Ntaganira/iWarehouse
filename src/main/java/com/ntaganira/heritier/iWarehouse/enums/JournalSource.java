package com.ntaganira.heritier.iWarehouse.enums;

import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.enums
 * - File      : JournalSource.java
 * - Date      : 2026. 10. 09.
 * - User      : Hntaganira
 * - Desc      : The business event a journal was posted for (ACC-04), with the page of its document.
 *               A new event = a constant here + chk_journal_entries_source in its module's migration.
 * </pre>
 */
public enum JournalSource {

    GOODS_RECEIPT("/goods-receipts/"),
    SHIPMENT("/shipments/"),
    CLAIM_OPENED("/shipments/"),
    CLAIM_SETTLED("/shipments/"),
    CLAIM_REJECTED("/shipments/"),
    CUTTING_JOB("/cutting-jobs/"),
    ADJUSTMENT("/stock-adjustments/"),
    OPENING_STOCK(null);

    private final String path;

    JournalSource(String path) {
        this.path = path;
    }

    /** Link to the source document, or null when there is none. */
    public String link(UUID sourceId) {
        return path == null || sourceId == null ? null : path + sourceId;
    }
}
