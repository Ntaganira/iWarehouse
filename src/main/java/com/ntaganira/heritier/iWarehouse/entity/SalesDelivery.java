package com.ntaganira.heritier.iWarehouse.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Immutable;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.entity
 * - File      : SalesDelivery.java
 * - Date      : 2026. 10. 09.
 * - User      : Hntaganira
 * - Desc      : A piece of a custom size handed over to the customer (SRS 5.3 step 5): the invoice and line it
 *               fulfils and the unit sold. A unit is handed over once (uk_sales_deliveries_unit). Append-only
 *               (trg_sales_deliveries_append_only): each row is its own record, so it is not audited.
 * </pre>
 */
@Entity
@Immutable
@Table(name = "sales_deliveries")
@Getter
@Setter
@NoArgsConstructor
public class SalesDelivery {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "invoice_id", nullable = false)
    private UUID invoiceId;

    @Column(name = "line_id", nullable = false)
    private UUID lineId;

    @Column(name = "stock_unit_id", nullable = false)
    private UUID stockUnitId;

    @Column(name = "unit_code", nullable = false, length = 30)
    private String unitCode;

    @Column(name = "delivered_at", nullable = false)
    private LocalDateTime deliveredAt;

    @Column(name = "user_id")
    private Long userId;

    @Column(nullable = false, length = 50)
    private String username;
}
