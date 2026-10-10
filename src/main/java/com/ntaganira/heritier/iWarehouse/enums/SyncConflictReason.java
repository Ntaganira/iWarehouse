package com.ntaganira.heritier.iWarehouse.enums;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.enums
 * - File      : SyncConflictReason.java
 * - Date      : 2026. 10. 10.
 * - User      : Hntaganira
 * - Desc      : Why the server could not take a mobile sale as it was made (SYNC-05); kept for the supervisor, never
 *               dropped. Each has a message key sync.reason.NAME.
 * </pre>
 */
public enum SyncConflictReason {

    /** The driver is on no trip on the road. */
    NO_TRIP,
    /** The sale names a trip that is not the driver's trip on the road. */
    NOT_YOUR_TRIP,
    /** A unit is no longer on the vehicle: sold, moved or written off since. */
    UNIT_NOT_ON_VEHICLE,
    /** The invoice number was not given to this phone for this trip. */
    NUMBER_NOT_ISSUED,
    /** The invoice number is on another sale already. */
    NUMBER_USED,
    /** No price for a glass in the prices of the trip. */
    NO_PRICE,
    /** A price lower than the driver's discount limit allows (MPOS-04). */
    PRICE_BELOW_LIMIT,
    /** The phone's total differs from the prices of the trip. */
    TOTAL_MISMATCH,
    /** The payments do not add up to the total, or a mobile-money payment has no reference. */
    PAYMENT_MISMATCH,
    /** The sale cannot be read: no unit, a unit twice, a field missing. */
    INVALID
}
