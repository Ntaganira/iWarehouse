package com.ntaganira.heritier.iWarehouse.enums;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.enums
 * - File      : CuttingJobStatus.java
 * - Date      : 2026. 10. 08.
 * - User      : Hntaganira
 * - Desc      : Life of a cutting job (PRD-01..03). Draft while its pieces are listed; in progress once
 *               the operator took a source unit for it (the unit is IN_CUTTING); completed when the cut
 *               is recorded (the source is consumed, pieces and off-cuts are in stock); cancelled with a
 *               reason before that.
 * </pre>
 */
public enum CuttingJobStatus {

    DRAFT,
    IN_PROGRESS,
    COMPLETED,
    CANCELLED
}
