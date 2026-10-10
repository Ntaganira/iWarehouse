package com.ntaganira.heritier.iWarehouse.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.entity
 * - File      : AlertState.java
 * - Date      : 2026. 10. 10.
 * - User      : Hntaganira
 * - Desc      : A condition the alerts watch (RPT-06), by key ("LOW_STOCK:&lt;product id&gt;"): raised once when it starts,
 *               cleared when it ends, so people are told once, and again only if it comes back. System state, not audited.
 * </pre>
 */
@Entity
@Table(name = "alert_states")
@Getter
@Setter
@NoArgsConstructor
public class AlertState {

    @Id
    @Column(name = "alert_key", length = 120)
    private String key;

    @Column(name = "raised_at", nullable = false)
    private LocalDateTime raisedAt;

    @Column(name = "cleared_at")
    private LocalDateTime clearedAt;

    public boolean isActive() {
        return clearedAt == null;
    }
}
