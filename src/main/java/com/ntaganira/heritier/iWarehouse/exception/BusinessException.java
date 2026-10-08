package com.ntaganira.heritier.iWarehouse.exception;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.exception
 * - File      : BusinessException.java
 * - Date      : 2026. 10. 07.
 * - User      : Hntaganira
 * - Desc      : A business rule refused the request. Carries a message key (shown in the user's
 *               language by the controller) and, when the problem belongs to one form field, that field.
 * </pre>
 */
public class BusinessException extends RuntimeException {

    private final String field;
    private final String messageKey;
    private final transient Object[] args;

    private BusinessException(String field, String messageKey, Object[] args) {
        super(messageKey);
        this.field = field;
        this.messageKey = messageKey;
        this.args = args;
    }

    /** A rule about the request as a whole (shown as a toast). */
    public static BusinessException of(String messageKey, Object... args) {
        return new BusinessException(null, messageKey, args);
    }

    /** A rule about one form field (shown next to that field). */
    public static BusinessException onField(String field, String messageKey, Object... args) {
        return new BusinessException(field, messageKey, args);
    }

    public String getField() {
        return field;
    }

    public String getMessageKey() {
        return messageKey;
    }

    public Object[] getArgs() {
        return args;
    }
}
