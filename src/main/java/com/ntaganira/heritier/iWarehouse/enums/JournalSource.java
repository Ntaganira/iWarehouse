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
    OPENING_STOCK(null),
    SALES_INVOICE("/invoices/"),
    TILL_OPENED("/till-sessions/"),
    TILL_CLOSED("/till-sessions/"),
    SALES_DELIVERY("/invoices/"),
    SALES_BALANCE("/invoices/"),
    CREDIT_NOTE("/credit-notes/"),
    CUSTOMER_PAYMENT("/customer-payments/"),
    SUPPLIER_INVOICE("/supplier-invoices/"),
    SUPPLIER_PAYMENT("/supplier-payments/"),
    FX_REVALUATION("/accounting/fx-revaluations/"),
    MANUAL_JOURNAL("/accounting/manual-journals/"),
    /** A sale from a vehicle (ACC-06): cash to the driver's float. */
    MOBILE_SALE("/invoices/"),
    /** A trip reconciled at the end of the day (FLT-09): its accepted breakage to spoilage. */
    TRIP_RETURN("/trips/"),
    /** A unit missing after a trip written off or charged to the driver (FLT-10, FLT-11). */
    AUDIT_CASE("/audit-cases/"),
    /** A driver's float cleared by the accountant (ACC-06, ACC-07). */
    FLOAT_CLEARANCE("/floats/"),
    /** A pay run approved: salaries and the employer's contributions, what is withheld and the net pay owed. */
    PAYROLL("/payroll/runs/"),
    /** A pay run's salaries paid. */
    PAYROLL_PAYMENT("/payroll/runs/"),
    /** A vehicle's running cost (fuel, a service, a repair, parts...), paid, owed to the garage or paid from a driver's float. */
    VEHICLE_COST("/vehicle-costs/"),
    /** A customer's debt written off on approval (F-14): bad debts against the receivable. */
    RECEIVABLE_WRITE_OFF("/accounting/write-offs/"),
    /** Takings banked from the vault (F-13): Dr Bank / Cr Cash Vault. */
    CASH_DEPOSIT("/cash-deposits/"),
    /** Collections banked during a trip: Dr Bank or Mobile Money / Cr Driver Float naming the driver. */
    TRIP_DEPOSIT("/trips/"),
    /** A damage loss charged to whoever pays it back (F-07 C). */
    DAMAGE_RECOVERY("/damage/"),
    /** An expense bill approved: Dr its categories' accounts and VAT Input / Cr Payable naming the supplier (CMP 01). */
    EXPENSE_BILL("/expenses/"),
    /** A petty cash fund topped up (Dr Petty Cash / Cr Vault or Bank) or its cash returned when it closes. */
    PETTY_CASH_REPLENISHMENT("/petty-cash/funds/"),
    /** A petty cash voucher approved: Dr its category's account / Cr Petty Cash naming the site (F-16). */
    PETTY_CASH_VOUCHER("/petty-cash/vouchers/"),
    /** A petty cash count's difference: to or from Cash Over and Short. */
    PETTY_CASH_COUNT("/petty-cash/funds/"),
    /** A staff advance paid: Dr Staff Advances / Cr what paid it (CMP 08). */
    STAFF_ADVANCE("/payroll/advances/"),
    /** A staff advance retired: Dr what it paid for and the cash returned / Cr Staff Advances (CMP 08). */
    ADVANCE_RETIREMENT("/payroll/advances/");

    private final String path;

    JournalSource(String path) {
        this.path = path;
    }

    /** Link to the source document, or null when there is none. */
    public String link(UUID sourceId) {
        return path == null || sourceId == null ? null : path + sourceId;
    }
}
