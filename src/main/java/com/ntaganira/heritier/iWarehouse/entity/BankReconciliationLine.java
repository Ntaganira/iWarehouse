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
 * - File      : BankReconciliationLine.java
 * - Date      : 2026. 10. 09.
 * - User      : Hntaganira
 * - Desc      : A ledger line a reconciliation cleared (ACC-12), with its debit and credit. A line is cleared by one
 *               reconciliation in force. Append-only (trg_bank_reconciliation_lines_append_only): the reconciliation
 *               records it, so it is not audited.
 * </pre>
 */
@Entity
@Immutable
@Table(name = "bank_reconciliation_lines")
@Getter
@Setter
@NoArgsConstructor
public class BankReconciliationLine {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "reconciliation_id", nullable = false)
    private UUID reconciliationId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "journal_line_id", nullable = false)
    private JournalLine journalLine;

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal debit;

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal credit;
}
