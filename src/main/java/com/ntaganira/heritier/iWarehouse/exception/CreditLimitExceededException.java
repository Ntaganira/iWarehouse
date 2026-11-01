package com.ntaganira.heritier.iWarehouse.exception;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.exception
 * - File      : CreditLimitExceededException.java
 * - Date      : 2026. 10. 31.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : A counter credit is refused: the customer still owes on open invoices, or the
 *               amount is beyond the credit left (TASK.md). It is a BusinessException, so every
 *               page shows the message beside the credit field or as a toast.
 * </pre>
 */
public class CreditLimitExceededException extends BusinessException {

    private CreditLimitExceededException(String field, String messageKey, Object[] args) {
        super(field, messageKey, args);
    }

    public static CreditLimitExceededException refused(String field, String messageKey, Object... args) {
        return new CreditLimitExceededException(field, messageKey, args);
    }
}
