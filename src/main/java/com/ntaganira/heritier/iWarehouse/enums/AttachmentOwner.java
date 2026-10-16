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
    STOCK_ADJUSTMENT("PAGE_STOCK_ADJUSTMENTS", "/stock-adjustments/"),
    /** Insurance and inspection certificates, the registration card. */
    VEHICLE("PAGE_VEHICLES", "/vehicles/"),
    /** A copy of the licence: personal data (NFR-12), opened only by those who see it. */
    DRIVER("PERM_VIEW_DRIVER_DATA", "/drivers/"),
    /** The photos of breakage on the road (FLT-08) and any paper of the trip. */
    TRIP("PAGE_FLEET", "/trips/"),
    /** The receipt or invoice of a vehicle cost. */
    VEHICLE_COST("PAGE_VEHICLE_COSTS", "/vehicle-costs/"),
    /** The evidence for a credit note asked (F-14). */
    CREDIT_NOTE_REQUEST("PAGE_CREDIT_NOTES", "/credit-notes/requests/"),
    /** The evidence for a write-off: letters sent, a court ruling, the customer's closure (F-14). */
    RECEIVABLE_WRITE_OFF("PAGE_RECEIVABLE_WRITE_OFFS", "/accounting/write-offs/"),
    /** A customer's know-your-customer papers: registration, TIN certificate, ID, guarantee (SDP 11, F-02). */
    CUSTOMER("PAGE_CUSTOMERS", "/customers/"),
    /** A supplier's onboarding papers: registration, TIN certificate, bank letter, references (CMP 08). */
    SUPPLIER("PAGE_SUPPLIERS", "/suppliers/"),
    /** The papers behind a credit application: financial statements, references, the guarantee (F-02). */
    CREDIT_APPLICATION("PAGE_CREDIT_APPLICATIONS", "/credit-applications/"),
    /** A transfer's delivery note, signed when it left and when it arrived (TCN-008). */
    SITE_TRANSFER("PAGE_SITE_TRANSFERS", "/site-transfers/"),
    /** The approved cutting drawing (IMP 10 step 1, CUT-006). */
    CUTTING_JOB("PAGE_PRODUCTION", "/cutting-jobs/"),
    /** The delivery note signed by the customer and brought back (IMP 9.3, DN-005). */
    DELIVERY_NOTE("PAGE_DELIVERY_NOTES", "/delivery-notes/"),
    /** The bank's stamped deposit slip (F-13). */
    CASH_DEPOSIT("PAGE_CASH_DEPOSITS", "/cash-deposits/"),
    /** The customer's signed reply to a balance confirmation (SDP 10). */
    BALANCE_CONFIRMATION("PAGE_BALANCE_CONFIRMATIONS", "/accounting/confirmations/"),
    /** The MoMo merchant statement and the signed end-of-day report (F-12). */
    DAILY_CLOSE("PAGE_DAILY_CLOSES", "/daily-close/"),
    /** A damage report: photos of the damage, the inspection, the insurer's papers (F-07). */
    DAMAGE_REPORT("PAGE_DAMAGE", "/damage/"),
    /** A stock count: the count sheets signed on paper (PF-06, F-08). */
    STOCK_COUNT("PAGE_STOCK_COUNTS", "/stock-counts/"),
    /** A count variance: what the investigation found (VR-010). */
    STOCK_VARIANCE("PAGE_STOCK_VARIANCES", "/stock-variances/"),
    /** An expense bill: the supplier's original invoice (CMP 07). */
    EXPENSE_BILL("PAGE_EXPENSE_BILLS", "/expenses/"),
    /** A purchase requisition: the written quotes, the RFQ or tender papers (CMP 06). */
    PURCHASE_REQUISITION("PAGE_REQUISITIONS", "/requisitions/");

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
