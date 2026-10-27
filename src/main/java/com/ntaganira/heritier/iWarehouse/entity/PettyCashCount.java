package com.ntaganira.heritier.iWarehouse.entity;

import com.ntaganira.heritier.iWarehouse.audit.AuditedEntity;
import com.ntaganira.heritier.iWarehouse.enums.PettyCashCountKind;
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
 * - File      : PettyCashCount.java
 * - Date      : 2026. 10. 16.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : A count of a petty cash fund (CMP 07): the custodian's daily count or Finance's surprise count, what the
 *               ledger expected, the cash counted and the difference, which needs a note and posts at once to Cash Over
 *               and Short (its journal). Never changed (append-only, trigger).
 * </pre>
 */
@Entity
@Immutable
@Table(name = "petty_cash_counts")
@AuditedEntity(ref = "countedOn")
@Getter
@Setter
@NoArgsConstructor
public class PettyCashCount extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "fund_id", nullable = false, updatable = false)
    private PettyCashFund fund;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10, updatable = false)
    private PettyCashCountKind kind;

    @Column(name = "counted_on", nullable = false, updatable = false)
    private LocalDate countedOn;

    @Column(name = "counted_by", nullable = false, length = 50, updatable = false)
    private String countedBy;

    @Column(name = "counted_by_id", updatable = false)
    private Long countedById;

    @Column(name = "counted_at", nullable = false, updatable = false)
    private LocalDateTime countedAt;

    @Column(nullable = false, precision = 18, scale = 2, updatable = false)
    private BigDecimal expected;

    @Column(nullable = false, precision = 18, scale = 2, updatable = false)
    private BigDecimal counted;

    @Column(nullable = false, precision = 18, scale = 2, updatable = false)
    private BigDecimal difference;

    @Column(length = 500, updatable = false)
    private String note;

    @Column(name = "journal_id", updatable = false)
    private UUID journalId;
}
