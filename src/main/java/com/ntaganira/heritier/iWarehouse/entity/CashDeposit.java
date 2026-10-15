package com.ntaganira.heritier.iWarehouse.entity;

import com.ntaganira.heritier.iWarehouse.audit.AuditedEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.entity
 * - File      : CashDeposit.java
 * - Date      : 2026. 10. 15.
 * - User      : Hntaganira
 * - Desc      : Takings banked (F-13, SDP 10): the closed tills it took (till_sessions.deposit_id), the bank account,
 *               the deposit slip's number, the amount, exactly their cash less their floats (banked in full, never
 *               netted). Posted when saved: Dr Bank / Cr Cash Vault. Never changed.
 * </pre>
 */
@Entity
@Table(name = "cash_deposits")
@AuditedEntity(ref = "number")
@Getter
@Setter
@NoArgsConstructor
public class CashDeposit extends BaseEntity {

    @Column(nullable = false, unique = true, length = 30, updatable = false)
    private String number;

    @Column(name = "deposit_date", nullable = false, updatable = false)
    private LocalDate depositDate;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false, updatable = false)
    private Account account;

    @Column(name = "slip_number", nullable = false, length = 40, updatable = false)
    private String slipNumber;

    @Column(nullable = false, precision = 18, scale = 2, updatable = false)
    private BigDecimal amount;

    @Column(length = 255, updatable = false)
    private String notes;

    @Column(name = "posted_at", nullable = false, updatable = false)
    private LocalDateTime postedAt;

    @Column(name = "posted_by", nullable = false, length = 50, updatable = false)
    private String postedBy;
}
