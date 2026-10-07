package com.ntaganira.heritier.iWarehouse.audit;

import java.lang.annotation.*;

/**
 * Marks an entity whose every CREATE / UPDATE / DELETE is written to data_change_logs
 * with full before/after snapshots (SRS AUD-02, AUD-03).
 *
 * <pre>
 * &#64;Entity
 * &#64;AuditedEntity(ref = "invoiceNumber")
 * public class Invoice extends BaseEntity { ... }
 * </pre>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface AuditedEntity {

    /** Field whose value is shown as the readable reference (e.g. "invoiceNumber"). Empty = id only. */
    String ref() default "";
}
