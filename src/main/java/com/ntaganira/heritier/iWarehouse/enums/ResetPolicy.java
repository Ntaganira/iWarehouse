package com.ntaganira.heritier.iWarehouse.enums;

/** When a numbering sequence starts again at 1; also decides the period part of the number (MD-07). */
public enum ResetPolicy {
    /** INV-WH-000123 */
    NEVER,
    /** INV-WH-2026-000123 */
    YEARLY,
    /** INV-WH-202610-000123 */
    MONTHLY
}
