package com.ntaganira.heritier.iWarehouse.enums;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.enums
 * - File      : ShipmentCostStatus.java
 * - Date      : 2026. 10. 08.
 * - User      : Hntaganira
 * - Desc      : An import cost line is a draft until posted. Posting fixes its rate and adds it to the
 *               crates' landed cost; a posted line is never changed (a credit note corrects it).
 * </pre>
 */
public enum ShipmentCostStatus {

    DRAFT,
    POSTED
}
