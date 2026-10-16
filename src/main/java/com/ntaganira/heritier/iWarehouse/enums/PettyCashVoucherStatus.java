package com.ntaganira.heritier.iWarehouse.enums;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.enums
 * - File      : PettyCashVoucherStatus.java
 * - Date      : 2026. 10. 16.
 * - User      : Hntaganira
 * - Desc      : A petty cash voucher: recorded by the custodian, submitted, approved (paid and posted) or rejected;
 *               cancelled by who recorded it; reversed once posted.
 * </pre>
 */
public enum PettyCashVoucherStatus {
    DRAFT,
    SUBMITTED,
    APPROVED,
    REJECTED,
    CANCELLED,
    REVERSED
}
