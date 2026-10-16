package com.ntaganira.heritier.iWarehouse.entity;

import com.ntaganira.heritier.iWarehouse.audit.AuditedEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import com.ntaganira.heritier.iWarehouse.enums.CashDirection;
import com.ntaganira.heritier.iWarehouse.enums.CashRepeat;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.entity
 * - File      : CashForecastItem.java
 * - Date      : 2026. 10. 16.
 * - User      : Hntaganira
 * - Desc      : A planned item of the cash-flow forecast (P.11): money expected in or out that no document carries yet (a loan
 *               repayment, rent, a capital purchase), once or repeating weekly or monthly until a day. Cancelled, never deleted.
 * </pre>
 */
@Entity
@Table(name = "cash_forecast_items")
@AuditedEntity(ref = "description")
@Getter
@Setter
@NoArgsConstructor
public class CashForecastItem extends BaseEntity {

    @Column(name = "expected_on", nullable = false)
    private LocalDate expectedOn;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 3)
    private CashDirection direction;

    @Column(nullable = false, length = 200)
    private String description;

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private CashRepeat repeats = CashRepeat.NONE;

    private LocalDate until;

    /** ACTIVE or CANCELLED. */
    @Column(nullable = false, length = 10)
    private String status = "ACTIVE";

    public boolean isActive() {
        return "ACTIVE".equals(status);
    }
}
