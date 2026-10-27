package com.ntaganira.heritier.iWarehouse.enums;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.enums
 * - File      : ChargeUnit.java
 * - Date      : 2026. 10. 08.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : What one unit of a price covers (MD-06): a square metre of glass (tempering), a metre
 *               of edge (edging, polishing, bevelling), a piece, a hole (drilling), or an accessory's
 *               roll, tube, kilogram, litre, set, box, pack or bag. Accessories are catalogue-only:
 *               their unit is only how the price is counted, never stock.
 * </pre>
 */
public enum ChargeUnit {
    M2,
    METRE,
    PIECE,
    HOLE,
    ROLL,
    TUBE,
    KG,
    LITRE,
    SET,
    BOX,
    PACK,
    BAG
}
