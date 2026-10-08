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
 * - File      : ServicePrice.java
 * - Date      : 2026. 10. 08.
 * - User      : Hntaganira
 * - Desc      : Price of one processing service on one price list, per the service's charge unit
 *               (MD-06). Like product prices, a cleared price stays as a row with no price.
 * </pre>
 */
@Entity
@Table(name = "price_list_services")
@AuditedEntity
@Getter
@Setter
@NoArgsConstructor
public class ServicePrice extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "price_list_id", nullable = false, updatable = false)
    private PriceList priceList;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "service_id", nullable = false, updatable = false)
    private ProcessingService service;

    /** RWF per charge unit; null = not priced on this list. */
    @Column(precision = 18, scale = 2)
    private BigDecimal price;
}
