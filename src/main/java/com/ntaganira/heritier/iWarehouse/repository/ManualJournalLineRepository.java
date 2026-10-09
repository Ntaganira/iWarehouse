package com.ntaganira.heritier.iWarehouse.repository;

import com.ntaganira.heritier.iWarehouse.entity.ManualJournalLine;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.repository
 * - File      : ManualJournalLineRepository.java
 * - Date      : 2026. 10. 09.
 * - User      : Hntaganira
 * - Desc      : Lines of manual journals (ACC-05). Append-only: save and finders only.
 * </pre>
 */
public interface ManualJournalLineRepository extends Repository<ManualJournalLine, UUID> {

    ManualJournalLine save(ManualJournalLine line);

    @EntityGraph(attributePaths = {"account"})
    List<ManualJournalLine> findByManualJournalIdOrderByLineNo(UUID manualJournalId);
}
