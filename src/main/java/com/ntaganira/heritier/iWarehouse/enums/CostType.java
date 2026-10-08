package com.ntaganira.heritier.iWarehouse.enums;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.enums
 * - File      : CostType.java
 * - Date      : 2026. 10. 08.
 * - User      : Hntaganira
 * - Desc      : Import cost components of a shipment (PRC-03). Customs duty is converted at the customs
 *               rate (RRA); the others at the default rate source of Settings.
 * </pre>
 */
public enum CostType {

    FREIGHT,
    INSURANCE,
    DUTY,
    CLEARING,
    PORT,
    TRANSPORT,
    OTHER;

    /** Rate source this cost is converted with; null means the default source. */
    public RateSource rateSource() {
        return this == DUTY ? RateSource.CUSTOMS : null;
    }
}
