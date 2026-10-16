package com.ntaganira.heritier.iWarehouse.entity;

import com.ntaganira.heritier.iWarehouse.audit.AuditedEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.entity
 * - File      : RequisitionQuote.java
 * - Date      : 2026. 10. 16.
 * - User      : Hntaganira
 * - Desc      : A supplier’s quote for a requisition (CMP 06): who, their reference and day, the amount; a supplier on the
 *               register when the buyer is one, otherwise by name (an RFQ answered by someone not yet registered).
 * </pre>
 */
@Entity
@Table(name = "requisition_quotes")
@AuditedEntity(ref = "supplierName")
@Getter
@Setter
@NoArgsConstructor
public class RequisitionQuote extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "requisition_id", nullable = false)
    private PurchaseRequisition requisition;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "supplier_id")
    private Supplier supplier;

    @Column(name = "supplier_name", nullable = false, length = 150)
    private String supplierName;

    @Column(name = "quote_ref", length = 60)
    private String quoteRef;

    @Column(name = "quote_date")
    private LocalDate quoteDate;

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal amount;

    @Column(length = 255)
    private String note;
}
