package com.ntaganira.heritier.iWarehouse.enums;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.enums
 * - File      : DocumentType.java
 * - Date      : 2026. 10. 07.
 * - User      : Hntaganira
 * - Desc      : Document types that get a number from a numbering sequence (MD-07), with the prefix a
 *               new sequence starts with. Stored by name in number_sequences.doc_type.
 * </pre>
 */
public enum DocumentType {

    INVOICE("INV"),
    QUOTATION("QUO"),
    SALES_ORDER("SO"),
    CREDIT_NOTE("CN"),
    RECEIPT("RCT"),
    PURCHASE_ORDER("PO"),
    GOODS_RECEIPT("GRN"),
    SHIPMENT("SHP"),
    TRANSFER("TRF"),
    ADJUSTMENT("ADJ"),
    STOCK_COUNT("CNT"),
    CUTTING_JOB("CUT"),
    TRIP("TRP"),
    JOURNAL("JV"),
    TILL_SESSION("TILL"),
    /** A discount or credit waiting for a manager at the counter (POS-05, POS-06). */
    SALE_APPROVAL("APR"),
    /** A supplier's invoice matched to its goods receipts (ACC-09): SINV-WH-2026-000001. */
    SUPPLIER_INVOICE("SINV"),
    /** A payment to a supplier (ACC-09): SPAY-WH-2026-000001. */
    SUPPLIER_PAYMENT("SPAY"),
    /** Open foreign balances revalued at a month's end (ACC-08): FXR-WH-2026-000001. */
    FX_REVALUATION("FXR"),
    /** Not documents, but numbered the same way (MD-04, MD-05): CUS-WH-00001, SUP-WH-0001. */
    CUSTOMER("CUS"),
    SUPPLIER("SUP"),
    /** Label code of a stock unit (INV-03): U-WH-000001, never reset. */
    STOCK_UNIT("U");

    private final String defaultPrefix;

    DocumentType(String defaultPrefix) {
        this.defaultPrefix = defaultPrefix;
    }

    public String getDefaultPrefix() {
        return defaultPrefix;
    }
}
