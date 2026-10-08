package com.ntaganira.heritier.iWarehouse.enums;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.enums
 * - File      : BreakageReason.java
 * - Date      : 2026. 10. 08.
 * - User      : Hntaganira
 * - Desc      : Reason code of glass broken while cutting (PRD-08), for the breakage report. Kept in
 *               chk_cutting_job_outputs_reason_code (V12).
 * </pre>
 */
public enum BreakageReason {

    HANDLING,
    CUTTING_ERROR,
    GLASS_DEFECT,
    TOOL,
    OTHER
}
