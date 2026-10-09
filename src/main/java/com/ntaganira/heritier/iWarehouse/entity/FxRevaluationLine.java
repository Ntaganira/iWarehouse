package com.ntaganira.heritier.iWarehouse.entity;

import com.ntaganira.heritier.iWarehouse.enums.RateSource;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Immutable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.entity
 * - File      : FxRevaluationLine.java
 * - Date      : 2026. 10. 09.
 * - User      : Hntaganira
 * - Desc      : One open foreign balance a revaluation revalued (ACC-08): the account, the supplier (none on
 *               Accrued Import Charges), what was owed in the currency and in RWF as booked, the rate applied
 *               (rate, date, source) and the RWF at it; booked less revalued is the gain (positive) or loss.
 *               Append-only (trg_fx_revaluation_lines_append_only): the revaluation records it, so it is not audited.
 * </pre>
 */
@Entity
@Immutable
@Table(name = "fx_revaluation_lines")
@Getter
@Setter
@NoArgsConstructor
public class FxRevaluationLine {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "revaluation_id", nullable = false)
    private UUID revaluationId;

    @Column(name = "line_no", nullable = false)
    private int lineNo;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "supplier_id")
    private Supplier supplier;

    @Column(name = "currency_code", nullable = false, length = 3)
    private String currencyCode;

    /** Owed in the currency (credits less debits). */
    @Column(name = "fx_owed", nullable = false, precision = 18, scale = 2)
    private BigDecimal fxOwed;

    /** Owed in RWF as booked. */
    @Column(name = "base_owed", nullable = false, precision = 18, scale = 2)
    private BigDecimal baseOwed;

    @Column(nullable = false, precision = 18, scale = 6)
    private BigDecimal rate;

    @Column(name = "rate_date", nullable = false)
    private LocalDate rateDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "rate_source", nullable = false, length = 10)
    private RateSource rateSource;

    /** Owed in RWF at the rate. */
    @Column(name = "revalued_owed", nullable = false, precision = 18, scale = 2)
    private BigDecimal revaluedOwed;

    /** Booked less revalued: positive a gain, negative a loss. */
    @Column(name = "gain_loss", nullable = false, precision = 18, scale = 2)
    private BigDecimal gainLoss;
}
