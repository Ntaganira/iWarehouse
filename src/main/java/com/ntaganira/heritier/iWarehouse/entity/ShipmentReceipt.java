package com.ntaganira.heritier.iWarehouse.entity;

import com.ntaganira.heritier.iWarehouse.audit.AuditedEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.entity
 * - File      : ShipmentReceipt.java
 * - Date      : 2026. 10. 08.
 * - User      : Hntaganira
 * - Desc      : A posted goods receipt that came in a shipment: its crates share the shipment's import
 *               costs. A receipt belongs to one shipment (uk_shipment_receipts_receipt). The receipt
 *               number is copied so the change log shows which receipt was added or removed.
 * </pre>
 */
@Entity
@Table(name = "shipment_receipts")
@AuditedEntity(ref = "receiptNumber")
@Getter
@Setter
@NoArgsConstructor
public class ShipmentReceipt extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "shipment_id", nullable = false, updatable = false)
    private Shipment shipment;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "goods_receipt_id", nullable = false, updatable = false)
    private GoodsReceipt goodsReceipt;

    @Column(name = "receipt_number", nullable = false, length = 30, updatable = false)
    private String receiptNumber;
}
