package com.ntaganira.heritier.iWarehouse.entity;

import com.ntaganira.heritier.iWarehouse.audit.AuditedEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.entity
 * - File      : PurchaseRequisitionLine.java
 * - Date      : 2026. 10. 16.
 * - User      : Hntaganira
 * - Desc      : An item a requisition asks for: what, how many, at what estimated unit cost (CMP 06).
 * </pre>
 */
@Entity
@Table(name = "purchase_requisition_lines")
@AuditedEntity(ref = "item")
@Getter
@Setter
@NoArgsConstructor
public class PurchaseRequisitionLine extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "requisition_id", nullable = false)
    private PurchaseRequisition requisition;

    @Column(name = "line_no", nullable = false)
    private int lineNo;

    @Column(nullable = false, length = 200)
    private String item;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal quantity;

    @Column(length = 20)
    private String unit;

    @Column(name = "unit_cost", nullable = false, precision = 18, scale = 2)
    private BigDecimal unitCost;

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal amount;
}
