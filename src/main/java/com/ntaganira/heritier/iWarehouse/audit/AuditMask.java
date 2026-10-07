package com.ntaganira.heritier.iWarehouse.audit;

import java.lang.annotation.*;

/**
 * Masks a field's value as "***" in audit snapshots (AUD-06). The field still shows
 * as changed, but its value is never stored. Fields listed in
 * app.audit.masked-fields (password, token, ...) are masked even without this annotation.
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface AuditMask {
}
