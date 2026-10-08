package com.ntaganira.heritier.iWarehouse.config;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.config
 * - File      : Messages.java
 * - Date      : 2026. 10. 07.
 * - User      : Hntaganira
 * - Desc      : Message bundle lookup in the current request's language (flash toasts, form errors)
 * </pre>
 */
@Component
public class Messages {

    private final MessageSource messageSource;

    public Messages(MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    /** The message for key, or the key itself when the bundle has no entry. */
    public String get(String key, Object... args) {
        return messageSource.getMessage(key, args, key, LocaleContextHolder.getLocale());
    }
}
