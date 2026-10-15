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
    /** An invoice made on a phone from a vehicle (MPOS): numbers handed to the phone in a block at trip start (SYNC-01). */
    MOBILE_INVOICE("MINV"),
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
    /** A journal asked for by account and approved by another person (ACC-05): MJ-WH-2026-000001. */
    MANUAL_JOURNAL("MJ"),
    /** A bank or mobile-money statement reconciled with the ledger (ACC-12): REC-WH-2026-000001. */
    BANK_RECONCILIATION("REC"),
    /** Units missing or unexpected when a trip came back (FLT-10): AC-WH-2026-000001. */
    AUDIT_CASE("AC"),
    /** A driver's float counted and cleared by the accountant (ACC-06): FC-WH-2026-000001. */
    FLOAT_CLEARANCE("FC"),
    /** A month's pay run (payroll): PAY-WH-2026-000001. */
    PAY_RUN("PAY"),
    /** A vehicle's running cost: VC-WH-2026-000001. */
    VEHICLE_COST("VC"),
    /** A credit note asked for, before it is approved and issued (F-14): CNR-WH-2026-000001. */
    CREDIT_NOTE_REQUEST("CNR"),
    /** A customer's debt written off on approval (F-14): RWO-WH-2026-000001. */
    RECEIVABLE_WRITE_OFF("RWO"),
    /** A supplier payment prepared, before it is authorised and paid (CMP 07): PRQ-WH-2026-000001. */
    SUPPLIER_PAYMENT_REQUEST("PRQ"),
    /** Credit terms asked for a customer, assessed, reviewed and approved (F-02): CAP-WH-2026-000001. */
    CREDIT_APPLICATION("CAP"),
    /** Prices of a price list changed, waiting for approval (SDP 5): PCH-WH-2026-000001. */
    PRICE_CHANGE("PCH"),
    /** Glass sent from one site to another (IMP 11, TRF-007): STR-WH-2026-000001. */
    SITE_TRANSFER("STR"),
    /** The ticket of a stock movement, verified, countersigned and posted (F-05, Annex I): TT-WH-2026-000001. */
    TRANSACTION_TICKET("TT"),
    /** The delivery note and goods release authority (F-04, DN-005): DN-WH-2026-000001. */
    DELIVERY_NOTE("DN"),
    /** Takings banked in full with the deposit slip (F-13). */
    CASH_DEPOSIT("BNK"),
    /** Collections a driver banked or sent by MoMo during a trip (BP 7.3 rule 4). */
    TRIP_DEPOSIT("TDP"),
    /** The day's close: the end-of-day sales report, signed (F-12). */
    DAILY_CLOSE("DC"),
    /** A payment reminder to a customer with an overdue balance (BP 5.1). */
    CUSTOMER_REMINDER("REM"),
    /** A balance confirmation sent to a customer (SDP 10). */
    BALANCE_CONFIRMATION("BC"),
    /** Not documents, but numbered the same way (MD-04, MD-05): CUS-WH-00001, SUP-WH-0001. */
    CUSTOMER("CUS"),
    SUPPLIER("SUP"),
    /** An employee's code (payroll): EMP-WH-0001, never reset. */
    EMPLOYEE("EMP"),
    /** Label code of a stock unit (INV-03): U-WH-000001, never reset. */
    STOCK_UNIT("U"),
    /** The invoice number EBM receipts are sent under (invcNo, TAX-02): a plain integer, never reset. */
    EBM_INVOICE("EBM");

    private final String defaultPrefix;

    DocumentType(String defaultPrefix) {
        this.defaultPrefix = defaultPrefix;
    }

    public String getDefaultPrefix() {
        return defaultPrefix;
    }
}
