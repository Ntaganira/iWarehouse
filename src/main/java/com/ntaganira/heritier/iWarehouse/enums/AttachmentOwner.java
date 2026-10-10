package com.ntaganira.heritier.iWarehouse.enums;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.enums
 * - File      : AttachmentOwner.java
 * - Date      : 2026. 10. 10.
 * - User      : Hntaganira
 * - Desc      : The records documents are kept on (SRS 3.1 file storage): goods receipts (delivery notes, photos of
 *               breakage on arrival), shipments (customs and freight documents), supplier invoices (the supplier's
 *               invoice) and stock adjustments (photos of broken glass). Each names the page whose authority opens its
 *               documents and its record's path. A new owner = a constant here + chk_attachments_owner in its migration.
 * </pre>
 */
public enum AttachmentOwner {

    GOODS_RECEIPT("PAGE_RECEIVING", "/goods-receipts/"),
    SHIPMENT("PAGE_SHIPMENTS", "/shipments/"),
    SUPPLIER_INVOICE("PAGE_SUPPLIER_INVOICES", "/supplier-invoices/"),
    STOCK_ADJUSTMENT("PAGE_STOCK_ADJUSTMENTS", "/stock-adjustments/");

    private final String page;
    private final String path;

    AttachmentOwner(String page, String path) {
        this.page = page;
        this.path = path;
    }

    /** The authority that opens the record, and so its documents. */
    public String page() {
        return page;
    }

    /** The record's page. */
    public String path(java.util.UUID id) {
        return path + id;
    }
}
