package com.ntaganira.heritier.iWarehouse.entity;

import com.ntaganira.heritier.iWarehouse.enums.JournalSource;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Immutable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.entity
 * - File      : JournalEntry.java
 * - Date      : 2026. 10. 09.
 * - User      : Hntaganira
 * - Desc      : A posted journal (ACC-04): the event and document it records, its date and total. Written
 *               in the event's transaction and never changed (trg_journal_entries_append_only); a mistake
 *               is corrected by a new journal. Its lines balance (trg_journal_*_balanced, at commit).
 * </pre>
 */
@Entity
@Immutable
@Table(name = "journal_entries")
@Getter
@Setter
@NoArgsConstructor
public class JournalEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 30)
    private String number;

    @Column(name = "entry_date", nullable = false)
    private LocalDate entryDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 20)
    private JournalSource sourceType;

    @Column(name = "source_id")
    private UUID sourceId;

    @Column(name = "source_number", length = 30)
    private String sourceNumber;

    @Column(nullable = false, length = 255)
    private String description;

    /** The debits, equal to the credits. */
    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal total;

    @Column(name = "reverses_id")
    private UUID reversesId;

    @Column(name = "posted_at", nullable = false)
    private LocalDateTime postedAt;

    @Column(name = "user_id")
    private Long userId;

    @Column(nullable = false, length = 50)
    private String username;

    /** The page of the document it was posted for, or null. */
    public String getSourceLink() {
        return sourceType.link(sourceId);
    }
}
