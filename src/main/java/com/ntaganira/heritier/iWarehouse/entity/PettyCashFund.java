package com.ntaganira.heritier.iWarehouse.entity;

import com.ntaganira.heritier.iWarehouse.audit.AuditedEntity;
import com.ntaganira.heritier.iWarehouse.enums.PettyCashFundStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.entity
 * - File      : PettyCashFund.java
 * - Date      : 2026. 10. 16.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : A petty cash fund (CMP 07, BP 5.1: the cashier keeps the imprest): one per site in use, its custodian and
 *               its float (the imprest, at most the Settings' maximum). What it holds is never kept here: it is the site's
 *               lines on the Petty Cash account (JournalService.locationBalances). Closed with a reason, its cash returned.
 * </pre>
 */
@Entity
@Table(name = "petty_cash_funds")
@AuditedEntity(ref = "code")
@Getter
@Setter
@NoArgsConstructor
public class PettyCashFund extends BaseEntity {

    @Column(nullable = false, unique = true, length = 20, updatable = false)
    private String code;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "location_id", nullable = false, updatable = false)
    private Location location;

    @Column(name = "custodian_user_id", nullable = false)
    private Long custodianUserId;

    @Column(name = "custodian_name", nullable = false, length = 50)
    private String custodianName;

    /** The float it is topped up to. */
    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal imprest;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private PettyCashFundStatus status = PettyCashFundStatus.ACTIVE;

    @Column(name = "opened_by", nullable = false, length = 50, updatable = false)
    private String openedBy;

    @Column(name = "opened_at", nullable = false, updatable = false)
    private LocalDateTime openedAt;

    @Column(name = "closed_by", length = 50)
    private String closedBy;

    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    @Column(name = "close_reason", length = 500)
    private String closeReason;

    @Column(name = "close_journal_id")
    private UUID closeJournalId;

    public boolean isActive() {
        return status == PettyCashFundStatus.ACTIVE;
    }
}
