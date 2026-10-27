package com.ntaganira.heritier.iWarehouse.entity;

import com.ntaganira.heritier.iWarehouse.audit.AuditedEntity;
import com.ntaganira.heritier.iWarehouse.enums.DailyCloseStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.entity
 * - File      : DailyClose.java
 * - Date      : 2026. 10. 15.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : The close of a business day (F-12, BP 7.1 rule 5, 7.5): the end-of-day report's figures as they stood
 *               when it was prepared (sales by method, collections, refunds, the tills' cash expected and counted, MoMo
 *               received against the merchant statement, credit granted, the EBM receipts and their first and last
 *               numbers), the variances explained; prepared again until signed by another person (the Director of
 *               Finance); a signed close is tested by the Internal Controller. One per day.
 * </pre>
 */
@Entity
@Table(name = "daily_closes")
@AuditedEntity(ref = "number")
@Getter
@Setter
@NoArgsConstructor
public class DailyClose extends BaseEntity {

    @Column(nullable = false, unique = true, length = 30, updatable = false)
    private String number;

    @Column(name = "business_day", nullable = false, unique = true, updatable = false)
    private LocalDate businessDay;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private DailyCloseStatus status = DailyCloseStatus.PREPARED;

    @Column(nullable = false)
    private int invoices;

    @Column(name = "sales_total", nullable = false, precision = 18, scale = 2)
    private BigDecimal salesTotal;

    @Column(name = "cash_sales", nullable = false, precision = 18, scale = 2)
    private BigDecimal cashSales;

    @Column(name = "momo_sales", nullable = false, precision = 18, scale = 2)
    private BigDecimal momoSales;

    @Column(name = "other_sales", nullable = false, precision = 18, scale = 2)
    private BigDecimal otherSales;

    @Column(name = "credit_sales", nullable = false, precision = 18, scale = 2)
    private BigDecimal creditSales;

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal collections;

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal refunds;

    @Column(name = "cash_expected", nullable = false, precision = 18, scale = 2)
    private BigDecimal cashExpected;

    @Column(name = "cash_counted", nullable = false, precision = 18, scale = 2)
    private BigDecimal cashCounted;

    @Column(name = "cash_variance", nullable = false, precision = 18, scale = 2)
    private BigDecimal cashVariance;

    @Column(name = "cash_to_bank", nullable = false, precision = 18, scale = 2)
    private BigDecimal cashToBank;

    @Column(name = "momo_received", nullable = false, precision = 18, scale = 2)
    private BigDecimal momoReceived;

    @Column(name = "momo_statement", precision = 18, scale = 2)
    private BigDecimal momoStatement;

    @Column(name = "momo_variance", nullable = false, precision = 18, scale = 2)
    private BigDecimal momoVariance;

    @Column(name = "ebm_receipts", nullable = false)
    private int ebmReceipts;

    @Column(name = "ebm_first")
    private Long ebmFirst;

    @Column(name = "ebm_last")
    private Long ebmLast;

    @Column(name = "ebm_unsigned", nullable = false)
    private int ebmUnsigned;

    @Column(name = "variance_note", length = 500)
    private String varianceNote;

    @Column(name = "prepared_by", nullable = false, length = 50)
    private String preparedBy;

    @Column(name = "prepared_by_id")
    private Long preparedById;

    @Column(name = "prepared_at", nullable = false)
    private LocalDateTime preparedAt;

    @Column(name = "signed_by", length = 50)
    private String signedBy;

    @Column(name = "signed_by_id")
    private Long signedById;

    @Column(name = "signed_at")
    private LocalDateTime signedAt;

    @Column(name = "sign_note", length = 255)
    private String signNote;

    @Column(name = "tested_by", length = 50)
    private String testedBy;

    @Column(name = "tested_by_id")
    private Long testedById;

    @Column(name = "tested_at")
    private LocalDateTime testedAt;

    @Column(name = "test_note", length = 500)
    private String testNote;

    public boolean isSigned() {
        return status == DailyCloseStatus.SIGNED;
    }

    public boolean isTested() {
        return testedAt != null;
    }

    /** A cash or MoMo difference was found. */
    public boolean isVariance() {
        return cashVariance.signum() != 0 || momoVariance.signum() != 0;
    }
}
