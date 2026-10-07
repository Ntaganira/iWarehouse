package com.ntaganira.heritier.iWarehouse.audit;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.function.Supplier;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.audit
 * - File      : AuditContext.java
 * - Date      : 2026. 10. 07.
 * - User      : Hntaganira
 * - Desc      : Per-request audit data (request id, IP, user agent, device, client time, reason).
 *               Filled by AuditContextFilter, read by the activity log and the change listener.
 * </pre>
 *
 * Services set a reason for changes that require one (AUD-09, SRS 4.13):
 * <pre>
 * AuditContext.withReason(form.getReason(), () -&gt; stockService.adjust(unitId, qty));
 * </pre>
 */
public final class AuditContext {

    private static final ThreadLocal<Data> CURRENT = ThreadLocal.withInitial(Data::new);

    private AuditContext() {
    }

    @Getter
    @Setter
    public static class Data {
        private String requestId;
        private String ipAddress;
        private String userAgent;
        private String deviceId;
        private LocalDateTime clientTime;
        private String reason;
    }

    public static Data current() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }

    /** Runs the action with a change reason attached to every change it makes, then restores the previous reason. */
    public static <T> T withReason(String reason, Supplier<T> action) {
        Data data = current();
        String previous = data.getReason();
        data.setReason(reason);
        try {
            return action.get();
        } finally {
            data.setReason(previous);
        }
    }

    public static void withReason(String reason, Runnable action) {
        withReason(reason, () -> {
            action.run();
            return null;
        });
    }
}
