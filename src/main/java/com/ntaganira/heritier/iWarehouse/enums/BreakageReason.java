package com.ntaganira.heritier.iWarehouse.enums;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.enums
 * - File      : BreakageReason.java
 * - Date      : 2026. 10. 08.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
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
