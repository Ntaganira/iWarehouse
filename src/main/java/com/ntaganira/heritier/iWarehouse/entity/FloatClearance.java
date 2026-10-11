package com.ntaganira.heritier.iWarehouse.entity;

import com.ntaganira.heritier.iWarehouse.audit.AuditedEntity;
import com.ntaganira.heritier.iWarehouse.enums.AccountKey;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.entity
 * - File      : FloatClearance.java
 * - Date      : 2026. 10. 11.
 * - User      : Hntaganira
 * - Desc      : The accountant's count of a trip's cash (ACC-06, ACC-07): what the trip's cash sales put on the driver's
 *               float, what the driver declared and what was counted, paid into the vault or the bank. Less than expected
 *               is a shortage on the driver's receivable, more an overage to Cash Over/Short. FC-WH-2026-000001, one per
 *               trip, posted when saved and never changed (trg_float_clearances_append_only).
 * </pre>
 */
@Entity
@Table(name = "float_clearances")
@AuditedEntity(ref = "number")
@Getter
@Setter
@NoArgsConstructor
public class FloatClearance extends BaseEntity {

    @Column(nullable = false, unique = true, length = 30, updatable = false)
    private String number;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "trip_id", nullable = false, unique = true, updatable = false)
    private Trip trip;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "driver_id", nullable = false, updatable = false)
    private Driver driver;

    /** The trip's cash sales: what its driver's float holds for it. */
    @Column(nullable = false, precision = 18, scale = 2, updatable = false)
    private BigDecimal expected;

    @Column(nullable = false, precision = 18, scale = 2, updatable = false)
    private BigDecimal declared;

    @Column(nullable = false, precision = 18, scale = 2, updatable = false)
    private BigDecimal counted;

    @Column(nullable = false, precision = 18, scale = 2, updatable = false)
    private BigDecimal shortage;

    @Column(nullable = false, precision = 18, scale = 2, updatable = false)
    private BigDecimal overage;

    /** Where the cash went: the main vault or the bank. */
    @Enumerated(EnumType.STRING)
    @Column(name = "deposit_account", nullable = false, length = 12, updatable = false)
    private AccountKey depositAccount;

    @Column(length = 60, updatable = false)
    private String reference;

    @Column(length = 255, updatable = false)
    private String note;

    @Column(name = "cleared_at", nullable = false, updatable = false)
    private LocalDateTime clearedAt;

    @Column(name = "cleared_by", nullable = false, length = 50, updatable = false)
    private String clearedBy;
}
