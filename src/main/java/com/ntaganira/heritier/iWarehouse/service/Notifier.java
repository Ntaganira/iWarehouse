package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.enums.NotificationKind;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : Notifier.java
 * - Date      : 2026. 10. 10.
 * - User      : Hntaganira
 * - Desc      : How a service tells people something (RPT-06, SRS 3.2: side effects by Spring events): it publishes an
 *               Alert, which NotificationService delivers once the service's transaction commits (nothing on a
 *               rollback) as in-app notifications and, when Settings say so, emails. An alert goes to the users holding
 *               a permission (the approvers, ALERT_LOW_STOCK...) or to one user (a requester). Titles and texts are
 *               message keys; their arguments are plain text (numbers already formatted).
 * </pre>
 */
@Component
public class Notifier {

    /** What to tell, and to whom: the holders of {@code permission} but {@code exceptUserId}, or {@code userId}. */
    public record Alert(NotificationKind kind, String permission, Long userId, Long exceptUserId, String titleKey, String messageKey,
                        List<String> args, String link) {
    }

    private final ApplicationEventPublisher events;

    public Notifier(ApplicationEventPublisher events) {
        this.events = events;
    }

    /** Tells the enabled users holding a permission, except one (the person who caused it). */
    public void holders(String permission, Long exceptUserId, NotificationKind kind, String titleKey, String messageKey, String link,
                        String... args) {
        events.publishEvent(new Alert(kind, permission, null, exceptUserId, titleKey, messageKey, text(args), link));
    }

    /** Tells one user (nobody when null). */
    public void user(Long userId, NotificationKind kind, String titleKey, String messageKey, String link, String... args) {
        if (userId != null) {
            events.publishEvent(new Alert(kind, null, userId, null, titleKey, messageKey, text(args), link));
        }
    }

    private static List<String> text(String... args) {
        return java.util.Arrays.stream(args).map(a -> a == null ? "" : a).toList();
    }
}
