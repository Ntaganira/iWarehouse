package com.ntaganira.heritier.iWarehouse.entity;

import com.ntaganira.heritier.iWarehouse.audit.AuditedEntity;
import com.ntaganira.heritier.iWarehouse.enums.RateSource;
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
 * - File      : ExchangeRate.java
 * - Date      : 2026. 10. 07.
 * - User      : Hntaganira
 * - Desc      : RWF for 1 unit of a currency on a date, from a source (ACC-02). One rate per currency,
 *               date and source. Documents copy the rate they use, so a correction (made with a
 *               reason) never changes a posted document. Rates are never deleted.
 * </pre>
 */
@Entity
@Table(name = "exchange_rates")
@AuditedEntity(ref = "currencyCode")
@Getter
@Setter
@NoArgsConstructor
public class ExchangeRate extends BaseEntity {

    @Column(name = "currency_code", nullable = false, length = 3, updatable = false)
    private String currencyCode;

    @Column(name = "rate_date", nullable = false, updatable = false)
    private LocalDate rateDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10, updatable = false)
    private RateSource source;

    /** RWF for 1 unit of the currency. */
    @Column(nullable = false, precision = 18, scale = 6)
    private BigDecimal rate;

    @Column(length = 255)
    private String note;
}
