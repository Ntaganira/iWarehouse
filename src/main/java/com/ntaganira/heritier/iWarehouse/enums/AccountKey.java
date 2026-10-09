package com.ntaganira.heritier.iWarehouse.enums;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.enums
 * - File      : AccountKey.java
 * - Date      : 2026. 10. 09.
 * - User      : Hntaganira
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
    FX_UNREALISED
}
