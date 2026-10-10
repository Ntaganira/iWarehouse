package com.ntaganira.heritier.iWarehouse.entity;

import com.ntaganira.heritier.iWarehouse.enums.NotificationKind;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.entity
 * - File      : Notification.java
 * - Date      : 2026. 10. 10.
 * - User      : Hntaganira
 * - Desc      : A message to one user (RPT-06), as iVura's: what it is about, its title and text as message keys with
 *               their arguments (shown in the reader's language), the page it points to, and when it was read. A message
 *               to a person, not a business record: not audited; reading it only sets read_at.
 * </pre>
 */
@Entity
@Table(name = "notifications")
@Getter
@Setter
@NoArgsConstructor
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private NotificationKind kind;

    @Column(name = "title_key", nullable = false, length = 100)
    private String titleKey;

    @Column(name = "message_key", length = 100)
    private String messageKey;

    /** The message arguments, a JSON array of strings. */
    @Column(length = 1000)
    private String args;

    @Column(length = 255)
    private String link;

    @Column(name = "read_at")
    private LocalDateTime readAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public boolean isRead() {
        return readAt != null;
    }
}
