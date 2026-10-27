package com.ntaganira.heritier.iWarehouse.enums;

import java.util.List;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.enums
 * - File      : SaleApprovalStatus.java
 * - Date      : 2026. 10. 09.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : Life of an approval request at the counter (POS-05, POS-06): pending until another person
 *               approves or rejects it (with a reason); when Settings ask for it, recommended first (F-03: the
 *               Director of Commercial recommends, the COO decides). It is withdrawn, with a note, when the cashier takes it
 *               back or it no longer applies: its line left the sale, the customer changed, the sale was
 *               cancelled or a newer request replaced it.
 * </pre>
 */
public enum SaleApprovalStatus {

    PENDING,
    RECOMMENDED,
    APPROVED,
    REJECTED,
    WITHDRAWN;

    /** Waiting for a decision. */
    public static final List<SaleApprovalStatus> WAITING = List.of(PENDING, RECOMMENDED);

    /** Still bearing on the sale: waiting, or approved. */
    public static final List<SaleApprovalStatus> OPEN = List.of(PENDING, RECOMMENDED, APPROVED);
}
