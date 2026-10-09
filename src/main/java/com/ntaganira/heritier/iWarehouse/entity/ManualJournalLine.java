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
 * - File      : ManualJournalLine.java
 * - Date      : 2026. 10. 09.
 * - User      : Hntaganira
 * - Desc      : A line of a manual journal (ACC-05): an account, a debit or a credit (RWF), a memo. Fixed when the
 *               journal is asked for. Append-only (trg_manual_journal_lines_append_only): the journal records it,
 *               so it is not audited.
 * </pre>
 */
@Entity
@Immutable
@Table(name = "manual_journal_lines")
@Getter
@Setter
@NoArgsConstructor
public class ManualJournalLine {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "manual_journal_id", nullable = false)
    private UUID manualJournalId;

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
}
