package com.ntaganira.heritier.iWarehouse.entity;

import com.ntaganira.heritier.iWarehouse.audit.AuditedEntity;
import com.ntaganira.heritier.iWarehouse.enums.AuthorityLevel;
import com.ntaganira.heritier.iWarehouse.enums.Incoterm;
import com.ntaganira.heritier.iWarehouse.enums.PurchaseOrderStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.entity
 * - File      : PurchaseOrder.java
 * - Date      : 2026. 10. 08.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : A purchase order in the supplier's currency (PRC-01): sheets by product, size and
 *               quantity, priced per m². The currency is the supplier's when the order is created and
 *               stays on the order. The lines are audited as their own records.
 * </pre>
 */
@Entity
@Table(name = "purchase_orders")
@AuditedEntity(ref = "number")
@Getter
@Setter
@NoArgsConstructor
public class PurchaseOrder extends BaseEntity {

    @Column(nullable = false, unique = true, length = 30, updatable = false)
    private String number;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "supplier_id", nullable = false)
    private Supplier supplier;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PurchaseOrderStatus status = PurchaseOrderStatus.DRAFT;

    @Column(name = "order_date", nullable = false)
    private LocalDate orderDate;

    @Column(name = "expected_date")
    private LocalDate expectedDate;

    @Column(name = "currency_code", nullable = false, length = 3)
    private String currencyCode;

    @Enumerated(EnumType.STRING)
    @Column(length = 3)
    private Incoterm incoterm;

    /** The supplier's proforma or quotation number. */
    @Column(name = "supplier_ref", length = 60)
    private String supplierRef;

    @Column(length = 500)
    private String notes;

    @Column(name = "ordered_at")
    private LocalDateTime orderedAt;

    @Column(name = "ordered_by", length = 50)
    private String orderedBy;

    /** Why the order was cancelled or closed short. */
    @Column(name = "closed_reason", length = 255)
    private String closedReason;

    /** RWF at the day's rate when it was placed above the approval limit (CMP 06). */
    @Column(name = "approval_amount", precision = 18, scale = 2)
    private BigDecimal approvalAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "authority_level", length = 10)
    private AuthorityLevel authorityLevel;

    @Column(name = "submitted_by", length = 50)
    private String submittedBy;

    @Column(name = "submitted_by_id")
    private Long submittedById;

    @Column(name = "submitted_at")
    private LocalDateTime submittedAt;

    @Column(name = "approved_by", length = 50)
    private String approvedBy;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    /** The approved requisition it uses (CMP 06: no commitment without one). */
    @Column(name = "requisition_id")
    private UUID requisitionId;

    /** The approver's note, or why the order was sent back. */
    @Column(name = "approval_note", length = 255)
    private String approvalNote;

    @OneToMany(mappedBy = "purchaseOrder", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("lineNo")
    private List<PurchaseOrderLine> lines = new ArrayList<>();
}
