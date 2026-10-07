package com.ntaganira.heritier.iWarehouse.entity;

import com.ntaganira.heritier.iWarehouse.enums.ChangeOperation;
import jakarta.persistence.*;
import lombok.Getter;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.entity
 * - File      : DataChangeLog.java
 * - Date      : 2026. 10. 07.
 * - User      : Hntaganira
 * - Desc      : Data change log (SRS 4.13, layer 2): the record BEFORE and AFTER a change.
 *               READ-ONLY in JPA. Rows are inserted only by DataChangeEventListener
 *               (plain JDBC inside the business transaction). Never save this entity.
 * </pre>
 */
@Entity
@Immutable
@Table(name = "data_change_logs")
@Getter
public class DataChangeLog {

    @Id
    private Long id;

    @Column(name = "request_id", length = 36)
    private String requestId;

    @Column(name = "entity_type", nullable = false, length = 100)
    private String entityType;

    @Column(name = "entity_id", nullable = false, length = 64)
    private String entityId;

    @Column(name = "entity_ref", length = 100)
    private String entityRef;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ChangeOperation operation;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "before_data", columnDefinition = "jsonb")
    private Map<String, Object> beforeData;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "after_data", columnDefinition = "jsonb")
    private Map<String, Object> afterData;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "changed_fields", columnDefinition = "text[]")
    private String[] changedFields;

    @Column(columnDefinition = "TEXT")
    private String reason;

    @Column(name = "user_id")
    private Long userId;

    @Column(length = 50)
    private String username;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Column(name = "user_agent")
    private String userAgent;

    @Column(name = "device_id", length = 100)
    private String deviceId;

    @Column(name = "client_time")
    private LocalDateTime clientTime;

    @Column(name = "server_time", nullable = false)
    private LocalDateTime serverTime;
}
