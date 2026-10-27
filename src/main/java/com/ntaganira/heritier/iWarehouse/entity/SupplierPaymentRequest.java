package com.ntaganira.heritier.iWarehouse.entity;

import com.ntaganira.heritier.iWarehouse.audit.AuditedEntity;
import com.ntaganira.heritier.iWarehouse.enums.AuthorityLevel;
import com.ntaganira.heritier.iWarehouse.enums.PaymentMethod;
import com.ntaganira.heritier.iWarehouse.enums.PaymentRequestStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.entity
 * - File      : SupplierPaymentRequest.java
 * - Date      : 2026. 10. 15.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : A supplier payment prepared (CMP 07; Board Paper Table 6: Treasury prepares, the Director of Finance and the
 *               COO authorise): the supplier, currency, amount, how it is paid and its reference, worked out in RWF at the
 *               day's rate. Authorised by another person, by two above the Settings amount (at least one of them holding
 *               the office the amount needs), then paid as a supplier payment at the rate of the day it is paid.
 * </pre>
 */
@Entity
@Table(name = "supplier_payment_requests")
@AuditedEntity(ref = "number")
@Getter
@Setter
@NoArgsConstructor
public class SupplierPaymentRequest extends BaseEntity {

    @Column(nullable = false, unique = true, length = 30, updatable = false)
    private String number;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "supplier_id", nullable = false, updatable = false)
    private Supplier supplier;

    @Column(name = "currency_code", nullable = false, length = 3, updatable = false)
    private String currencyCode;

    @Column(nullable = false, precision = 18, scale = 2, updatable = false)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 15, updatable = false)
    private PaymentMethod method;

    @Column(length = 60, updatable = false)
    private String reference;

    @Column(length = 255, updatable = false)
    private String notes;

    /** RWF at the rate of the day it was prepared. */
    @Column(name = "base_estimate", nullable = false, precision = 18, scale = 2, updatable = false)
    private BigDecimal baseEstimate;

    @Enumerated(EnumType.STRING)
    @Column(name = "authority_level", nullable = false, length = 10, updatable = false)
    private AuthorityLevel authorityLevel;

    /** 1, or 2 above the Settings amount (a SMALLINT column). */
    @Column(nullable = false, updatable = false)
    @JdbcTypeCode(SqlTypes.SMALLINT)
    private int authorisations;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private PaymentRequestStatus status;

    @Column(name = "prepared_by", nullable = false, length = 50, updatable = false)
    private String preparedBy;

    @Column(name = "prepared_by_id", updatable = false)
    private Long preparedById;

    @Column(name = "prepared_at", nullable = false, updatable = false)
    private LocalDateTime preparedAt;

    @Column(name = "first_by", length = 50)
    private String firstBy;

    @Column(name = "first_by_id")
    private Long firstById;

    /** The office the first authoriser held. */
    @Enumerated(EnumType.STRING)
    @Column(name = "first_level", length = 10)
    private AuthorityLevel firstLevel;

    @Column(name = "first_at")
    private LocalDateTime firstAt;

    @Column(name = "first_note", length = 255)
    private String firstNote;

    @Column(name = "second_by", length = 50)
    private String secondBy;

    @Column(name = "second_by_id")
    private Long secondById;

    @Column(name = "second_at")
    private LocalDateTime secondAt;

    @Column(name = "second_note", length = 255)
    private String secondNote;

    @Column(name = "decided_by", length = 50)
    private String decidedBy;

    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    @Column(name = "decision_note", length = 255)
    private String decisionNote;

    @Column(name = "payment_id")
    private UUID paymentId;

    public boolean isWaiting() {
        return status.isWaiting();
    }

    public boolean isDual() {
        return authorisations == 2;
    }
}
