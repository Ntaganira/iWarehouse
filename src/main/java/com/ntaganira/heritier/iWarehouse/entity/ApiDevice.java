package com.ntaganira.heritier.iWarehouse.entity;

import com.ntaganira.heritier.iWarehouse.audit.AuditIgnore;
import com.ntaganira.heritier.iWarehouse.audit.AuditMask;
import com.ntaganira.heritier.iWarehouse.audit.AuditedEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.entity
 * - File      : ApiDevice.java
 * - Date      : 2026. 10. 10.
 * - User      : Hntaganira
 * - Desc      : A phone signed in to the mobile POS (NFR-10): its user, its own id (X-Device-Id), a name and the hash of
 *               its token (never the token). One live token per user and phone; signing in again on the same phone
 *               replaces it. Revoked, never deleted: a revoked phone's token is refused at once.
 * </pre>
 */
@Entity
@Table(name = "api_devices")
@AuditedEntity(ref = "name")
@Getter
@Setter
@NoArgsConstructor
public class ApiDevice extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private User user;

    @Column(nullable = false, length = 50, updatable = false)
    private String username;

    /** The phone's own id, sent as X-Device-Id with every request. */
    @Column(name = "device_key", nullable = false, length = 64, updatable = false)
    private String deviceKey;

    @Column(nullable = false, length = 60)
    private String name;

    @AuditMask
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "token_hash", nullable = false, length = 64, updatable = false)
    private String tokenHash;

    @Column(name = "user_agent", length = 255)
    private String userAgent;

    /** Bookkeeping: the last request it made (written at most every few minutes). */
    @AuditIgnore
    @Column(name = "last_seen_at")
    private LocalDateTime lastSeenAt;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    @Column(name = "revoked_by", length = 50)
    private String revokedBy;

    @Column(name = "revoke_reason", length = 255)
    private String revokeReason;

    public boolean isRevoked() {
        return revokedAt != null;
    }
}
