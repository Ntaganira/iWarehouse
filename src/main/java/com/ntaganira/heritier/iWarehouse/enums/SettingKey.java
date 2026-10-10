package com.ntaganira.heritier.iWarehouse.enums;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.enums
 * - File      : SettingKey.java
 * - Date      : 2026. 10. 07.
 * - User      : Hntaganira
 * - Desc      : Every configurable setting (ADM-03): its row key in the settings table, the matching
 *               SettingsDto property and the fallback used if the row is missing. A new setting is a
 *               new constant here, a field on SettingsDto and a seed row in its module's migration.
 * </pre>
 */
public enum SettingKey {

    COMPANY_NAME("company.name", "companyName", "iWarehouse"),
    COMPANY_TIN("company.tin", "companyTin", null),
    COMPANY_ADDRESS("company.address", "companyAddress", null),
    COMPANY_PHONE("company.phone", "companyPhone", null),
    COMPANY_EMAIL("company.email", "companyEmail", null),
    /** Branch used when a document is numbered without an explicit branch (MD-07). */
    BRANCH_CODE("company.branch-code", "branchCode", "WH"),

    /** Leftovers at least this big (m2) become off-cut units (PRD-04). */
    OFFCUT_MIN_AREA("production.offcut.min-area", "offcutMinArea", "0.25"),
    /** ...and with both sides at least this long (mm) (PRD-04). */
    OFFCUT_MIN_SIDE("production.offcut.min-side", "offcutMinSide", "300"),
    /** kg per m2 per mm of thickness: weight = area x thickness x density. */
    GLASS_DENSITY("production.glass-density", "glassDensity", "2.5"),
    /** Glass held longer than this many days is slow-moving (RPT-02). */
    SLOW_MOVING_DAYS("stock.slow-moving-days", "slowMovingDays", "90"),

    /** Default minimum chargeable area per piece (m2) for new price lists (MD-06). */
    MIN_CHARGEABLE_AREA("pricing.min-chargeable-area", "minChargeableArea", "0.25"),
    /** Days a quotation's prices hold by default (POS-03). */
    QUOTATION_VALIDITY_DAYS("sales.quotation-validity-days", "quotationValidityDays", "14"),

    /** The smallest deposit on an order, % of its total (POS-08); the glass taken at once is paid in full. */
    DEPOSIT_MIN_PERCENT("sales.deposit-min-percent", "depositMinPercent", "50"),

    /** Stock adjustments worth more than this (RWF) need supervisor approval (INV-07). */
    ADJUSTMENT_APPROVAL_LIMIT("approval.adjustment-limit", "adjustmentApprovalLimit", "0"),
    /** Discounts and price overrides above this percentage need manager approval (POS-06). */
    DISCOUNT_APPROVAL_PERCENT("approval.discount-limit-percent", "discountApprovalPercent", "0"),

    /** Rate source documents use unless they ask for another (ACC-02). A RateSource name. */
    DEFAULT_RATE_SOURCE("currency.default-rate-source", "defaultRateSource", "BNR"),
    /** Documents refuse a rate older than this many days, so a forgotten update is noticed (ACC-02). */
    MAX_RATE_AGE_DAYS("currency.max-rate-age-days", "maxRateAgeDays", "7"),

    /** Alerts go by email too (RPT-06), when a mail account is configured. */
    ALERT_EMAIL("alerts.email", "alertEmail", "false");

    private final String key;
    private final String property;
    private final String defaultValue;

    SettingKey(String key, String property, String defaultValue) {
        this.key = key;
        this.property = property;
        this.defaultValue = defaultValue;
    }

    public String key() {
        return key;
    }

    public String property() {
        return property;
    }

    public String defaultValue() {
        return defaultValue;
    }
}
