package com.ntaganira.heritier.iWarehouse.entity;

import com.ntaganira.heritier.iWarehouse.audit.AuditedEntity;
import com.ntaganira.heritier.iWarehouse.enums.ManualJournalStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.entity
 * - File      : ManualJournal.java
 * - Date      : 2026. 10. 09.
 * - User      : Hntaganira
 * - Desc      : A journal asked for by account (ACC-05): its date, description and balanced lines
 *               (ManualJournalLine, fixed when asked for). Another person approves it (the journal posts) or
 *               rejects it; the requester can withdraw it. Once posted it is reversed, never deleted: the
 *               reversing journal, its date and reason are kept here.
 * </pre>
 */
@Entity
@Table(name = "manual_journals")
@AuditedEntity(ref = "number")
@Getter
@Setter
@NoArgsConstructor
public class ManualJournal extends BaseEntity {

    @Column(nullable = false, unique = true, length = 30, updatable = false)
    private String number;

    @Column(name = "entry_date", nullable = false, updatable = false)
    private LocalDate entryDate;

    @Column(nullable = false, length = 255, updatable = false)
    private String description;

    /** The debits, equal to the credits. */
    @Column(nullable = false, precision = 18, scale = 2, updatable = false)
    private BigDecimal total;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ManualJournalStatus status;

    @Column(name = "requested_by_id", updatable = false)
    private Long requestedById;

    @Column(name = "requested_by", nullable = false, length = 50, updatable = false)
    private String requestedBy;

    @Column(name = "requested_at", nullable = false, updatable = false)
    private LocalDateTime requestedAt;

    /** Approved, rejected or withdrawn by. */
    @Column(name = "decided_by", length = 50)
    private String decidedBy;

    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    /** The reason of a rejection or withdrawal, or the approver's note. */
    @Column(name = "decision_note", length = 255)
    private String decisionNote;

    @Column(name = "journal_id")
    private UUID journalId;

    @Column(name = "reversal_date")
    private LocalDate reversalDate;

    @Column(name = "reversal_reason", length = 255)
    private String reversalReason;

    @Column(name = "reversed_by", length = 50)
    private String reversedBy;

    @Column(name = "reversed_at")
    private LocalDateTime reversedAt;

    @Column(name = "reversal_journal_id")
    private UUID reversalJournalId;

    public boolean isPending() {
        return status == ManualJournalStatus.PENDING_APPROVAL;
    }
}
