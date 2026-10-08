package com.ntaganira.heritier.iWarehouse.entity;

import com.ntaganira.heritier.iWarehouse.audit.AuditedEntity;
import com.ntaganira.heritier.iWarehouse.enums.Incoterm;
import com.ntaganira.heritier.iWarehouse.enums.PurchaseOrderStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

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
 * - User      : Hntaganira
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

    @OneToMany(mappedBy = "purchaseOrder", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("lineNo")
    private List<PurchaseOrderLine> lines = new ArrayList<>();
}
