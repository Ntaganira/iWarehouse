package com.ntaganira.heritier.iWarehouse.audit;

import java.lang.annotation.*;

/**
 * Excludes a field from audit snapshots entirely (e.g. a cached or derived value).
 * Use for noise only. For secrets, prefer {@link AuditMask} so the change stays visible.
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface AuditIgnore {
}
