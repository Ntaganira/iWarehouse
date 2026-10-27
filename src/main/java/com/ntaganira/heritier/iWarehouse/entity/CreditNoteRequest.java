package com.ntaganira.heritier.iWarehouse.entity;

import com.ntaganira.heritier.iWarehouse.audit.AuditedEntity;
import com.ntaganira.heritier.iWarehouse.enums.AuthorityLevel;
import com.ntaganira.heritier.iWarehouse.enums.CreditNoteKind;
import com.ntaganira.heritier.iWarehouse.enums.CreditNoteRequestStatus;
import com.ntaganira.heritier.iWarehouse.enums.PaymentMethod;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.entity
 * - File      : CreditNoteRequest.java
 * - Date      : 2026. 10. 15.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : A credit note asked for (F-14): the return (units back to a rack or to cullet) or the pieces given up,
 *               the refund wanted, the reason and the totals worked out when asked. Reviewed by Finance (when Settings ask
 *               for it), approved by another person within the Delegation of Authority, then issued through the usual
 *               credit note (CreditNoteService), never more than was approved. What it asks for is fixed once asked.
 * </pre>
 */
@Entity
@Table(name = "credit_note_requests")
@AuditedEntity(ref = "number")
@Getter
@Setter
@NoArgsConstructor
public class CreditNoteRequest extends BaseEntity {

    @Column(nullable = false, unique = true, length = 30, updatable = false)
    private String number;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10, updatable = false)
    private CreditNoteKind kind;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "invoice_id", nullable = false, updatable = false)
    private SalesInvoice invoice;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false, updatable = false)
    private Customer customer;

    @Column(nullable = false, length = 255, updatable = false)
    private String reason;

    @Column(name = "refund_reason", nullable = false, length = 2, updatable = false)
    private String refundReason;

    @Enumerated(EnumType.STRING)
    @Column(name = "refund_method", length = 15, updatable = false)
    private PaymentMethod refundMethod;

    @Column(name = "refund_reference", length = 60, updatable = false)
    private String refundReference;

    /** Where returned glass goes back. */
    @Column(name = "location_id", updatable = false)
    private UUID locationId;

    @Column(name = "net_amount", nullable = false, precision = 18, scale = 2, updatable = false)
    private BigDecimal netAmount;

    @Column(name = "vat_amount", nullable = false, precision = 18, scale = 2, updatable = false)
    private BigDecimal vatAmount;

    @Column(name = "total_amount", nullable = false, precision = 18, scale = 2, updatable = false)
    private BigDecimal totalAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "authority_level", nullable = false, length = 10, updatable = false)
    private AuthorityLevel authorityLevel;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private CreditNoteRequestStatus status;

    @Column(name = "requested_by", nullable = false, length = 50, updatable = false)
    private String requestedBy;

    @Column(name = "requested_by_id", updatable = false)
    private Long requestedById;

    @Column(name = "requested_at", nullable = false, updatable = false)
    private LocalDateTime requestedAt;

    @Column(name = "reviewed_by", length = 50)
    private String reviewedBy;

    @Column(name = "reviewed_by_id")
    private Long reviewedById;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    @Column(name = "review_note", length = 255)
    private String reviewNote;

    @Column(name = "decided_by", length = 50)
    private String decidedBy;

    @Column(name = "decided_by_id")
    private Long decidedById;

    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    @Column(name = "decision_note", length = 255)
    private String decisionNote;

    @Column(name = "credit_note_id")
    private UUID creditNoteId;

    @Column(name = "issued_by", length = 50)
    private String issuedBy;

    @Column(name = "issued_at")
    private LocalDateTime issuedAt;

    /** RETURN: the units coming back. */
    @ElementCollection
    @CollectionTable(name = "credit_note_request_items", joinColumns = @JoinColumn(name = "request_id"))
    private Set<Item> items = new HashSet<>();

    /** CANCEL: the pieces given up per size. */
    @ElementCollection
    @CollectionTable(name = "credit_note_request_sizes", joinColumns = @JoinColumn(name = "request_id"))
    private Set<Size> sizes = new HashSet<>();

    public boolean isWaiting() {
        return status.isWaiting();
    }

    /** A unit coming back, to a rack or to cullet. */
    @Embeddable
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class Item {
        @Column(name = "unit_id", nullable = false)
        private UUID unitId;

        @Column(nullable = false)
        private boolean cullet;
    }

    /** Pieces of a size given up. */
    @Embeddable
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class Size {
        @Column(name = "line_id", nullable = false)
        private UUID lineId;

        @Column(nullable = false)
        private int quantity;
    }
}
