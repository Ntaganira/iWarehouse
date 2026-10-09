package com.ntaganira.heritier.iWarehouse.repository;

import com.ntaganira.heritier.iWarehouse.entity.JournalEntry;
import com.ntaganira.heritier.iWarehouse.enums.JournalSource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.repository
 * - File      : JournalEntryRepository.java
 * - Date      : 2026. 10. 09.
 * - User      : Hntaganira
 * - Desc      : Posted journals (ACC-04). Append-only: save and finders only.
 * </pre>
 */
public interface JournalEntryRepository extends Repository<JournalEntry, UUID>, JpaSpecificationExecutor<JournalEntry> {

    JournalEntry save(JournalEntry entry);

    Optional<JournalEntry> findById(UUID id);

    Page<JournalEntry> findAll(Specification<JournalEntry> spec, Pageable pageable);

    /** The journals of a document, oldest first (a shipment has one per posting, and its claim's). */
    List<JournalEntry> findBySourceTypeInAndSourceIdOrderByPostedAtAscNumberAsc(Collection<JournalSource> types, UUID sourceId);

    boolean existsBySourceTypeAndSourceId(JournalSource type, UUID sourceId);

    Optional<JournalEntry> findFirstBySourceType(JournalSource type);

    /** The journals that reverse this one. */
    List<JournalEntry> findByReversesIdOrderByNumber(UUID reversesId);

    /** The date of the first journal (the ledger's first month), null before any. */
    @Query("select min(e.entryDate) from JournalEntry e")
    LocalDate firstEntryDate();
}
