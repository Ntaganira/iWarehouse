package com.ntaganira.heritier.iWarehouse.entity;

import com.ntaganira.heritier.iWarehouse.audit.AuditedEntity;
import com.ntaganira.heritier.iWarehouse.enums.PettyCashReplenishmentKind;
import com.ntaganira.heritier.iWarehouse.enums.PettyCashSource;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Immutable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.entity
 * - File      : PettyCashReplenishment.java
 * - Date      : 2026. 10. 16.
 * - User      : Hntaganira
 * - Desc      : Money into a petty cash fund from the vault or the bank (a top-up, CMP 07: below 25 % of its float), or
 *               its cash returned when it closes (PCR-WH-2026-000001), with what it held before. Posted when saved and never
 *               changed (append-only, trigger).
 * </pre>
 */
@Entity
@Immutable
@Table(name = "petty_cash_replenishments")
@AuditedEntity(ref = "number")
@Getter
@Setter
@NoArgsConstructor
public class PettyCashReplenishment extends BaseEntity {

    @Column(nullable = false, unique = true, length = 30, updatable = false)
    private String number;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "fund_id", nullable = false, updatable = false)
    private PettyCashFund fund;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10, updatable = false)
    private PettyCashReplenishmentKind kind;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12, updatable = false)
    private PettyCashSource source;

    @Column(length = 60, updatable = false)
    private String reference;

    @Column(nullable = false, precision = 18, scale = 2, updatable = false)
    private BigDecimal amount;

    @Column(name = "txn_date", nullable = false, updatable = false)
    private LocalDate txnDate;

    @Column(name = "balance_before", nullable = false, precision = 18, scale = 2, updatable = false)
    private BigDecimal balanceBefore;

    @Column(name = "made_by", nullable = false, length = 50, updatable = false)
    private String madeBy;

    @Column(name = "made_by_id", updatable = false)
    private Long madeById;

    @Column(name = "made_at", nullable = false, updatable = false)
    private LocalDateTime madeAt;

    @Column(name = "journal_id", nullable = false, updatable = false)
    private UUID journalId;

    /** What it holds after this movement. */
    public BigDecimal getBalanceAfter() {
        return kind == PettyCashReplenishmentKind.TOP_UP ? balanceBefore.add(amount) : balanceBefore.subtract(amount);
    }
}
