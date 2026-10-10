package com.ntaganira.heritier.iWarehouse.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Immutable;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.entity
 * - File      : JournalLine.java
 * - Date      : 2026. 10. 09.
 * - User      : Hntaganira
 * - Desc      : One line of a journal: an account debited or credited in RWF (ACC-01). A line from a foreign
 *               document keeps its currency, amount and rate; a stock line its glass (AT-10 per glass); a
 *               payable line its supplier, a receivable line its customer. Append-only (trg_journal_lines_append_only).
 * </pre>
 */
@Entity
@Immutable
@Table(name = "journal_lines")
@Getter
@Setter
@NoArgsConstructor
public class JournalLine {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "entry_id", nullable = false)
    private JournalEntry entry;

    @Column(name = "line_no", nullable = false)
    private int lineNo;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal debit = BigDecimal.ZERO;

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal credit = BigDecimal.ZERO;

    @Column(length = 255)
    private String memo;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id")
    private Product product;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "supplier_id")
    private Supplier supplier;

    /** Receivable lines: the customer whose credit it is (POS-05). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id")
    private Customer customer;

    /** Driver Float lines: the driver whose float it is (ACC-06). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "driver_id")
    private Driver driver;

    @Column(name = "currency_code", length = 3)
    private String currencyCode;

    @Column(name = "fx_amount", precision = 18, scale = 2)
    private BigDecimal fxAmount;

    @Column(precision = 18, scale = 6)
    private BigDecimal rate;
}
