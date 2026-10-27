package com.ntaganira.heritier.iWarehouse.enums;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.enums
 * - File      : AccountKey.java
 * - Date      : 2026. 10. 09.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : The accounts the posting rules use (SRS 4.9.1), by role rather than by code: the
 *               accountant may rename or renumber the account behind each key (V16 seeds them). An
 *               account with a key stays active. A new posting rule needing a new account = a constant
 *               here + an account seeded with that key in its module's migration.
 * </pre>
 */
public enum AccountKey {

    CASH,
    CASH_VAULT,
    BANK,
    MOBILE_MONEY,
    DRIVER_FLOAT,
    RECEIVABLE,
    CLAIMS,
    DRIVER_SHORTAGE,
    VAT_INPUT,
    INVENTORY,
    PAYABLE,
    GRNI,
    IMPORT_ACCRUAL,
    CUSTOMER_DEPOSITS,
    VAT_OUTPUT,
    RETAINED_EARNINGS,
    OPENING_EQUITY,
    SALES,
    SALES_RETURNS,
    COGS,
    SPOILAGE,
    STOCK_ADJUSTMENT,
    STOCK_REVALUATION,
    CASH_OVER_SHORT,
    FX_GAIN_LOSS,
    FX_UNREALISED,
    /** Payroll (V40): salary advances recovered, PAYE and RSSB withheld, net pay owed, other deductions, the costs. */
    STAFF_ADVANCES,
    PAYE_PAYABLE,
    RSSB_PAYABLE,
    NET_PAY_PAYABLE,
    PAYROLL_DEDUCTIONS,
    SALARIES,
    EMPLOYER_CONTRIBUTIONS,
    /** Vehicle costs (V41): fuel; services, repairs, parts and tyres; washing, parking, tolls and the rest. */
    VEHICLE_FUEL,
    VEHICLE_MAINTENANCE,
    VEHICLE_OTHER,
    /** Receivables written off on approval (V43, F-14). */
    BAD_DEBTS,
    /** Damage losses charged to a customer, an employee, an insurer, a supplier or a driver (V49, F-07 C). */
    DAMAGE_RECOVERIES,
    /** Capital items bought on expense bills (V51, CMP 05 CAPEX). */
    FIXED_ASSETS,
    /** The expense accounts of expense bills, by category (V51, CMP 01). */
    EXPENSE_UTILITIES,
    EXPENSE_RENT,
    EXPENSE_COMMUNICATION,
    EXPENSE_PROFESSIONAL,
    EXPENSE_REPAIRS,
    EXPENSE_TRANSPORT,
    EXPENSE_TRAVEL,
    EXPENSE_OFFICE,
    EXPENSE_SECURITY,
    EXPENSE_BANK_CHARGES,
    EXPENSE_MARKETING,
    EXPENSE_TRAINING,
    EXPENSE_INSURANCE,
    EXPENSE_TAXES,
    EXPENSE_OTHER,
    /** The petty cash imprest of each site (CMP 07): its lines name the site. */
    PETTY_CASH,
    /** The fixed assets' depreciation to date (C.08): a credit balance against Fixed Assets. */
    ACCUMULATED_DEPRECIATION,
    /** Tax withheld from suppliers, owed to RRA (U.14). */
    WHT_PAYABLE,
    /** The month's depreciation (C.08, U.12). */
    DEPRECIATION,
    /** What a fixed asset fetched against its book value: a loss is a debit, a gain a credit. */
    ASSET_DISPOSAL
}
