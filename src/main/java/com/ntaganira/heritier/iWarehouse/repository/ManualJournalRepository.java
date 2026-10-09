package com.ntaganira.heritier.iWarehouse.repository;

import com.ntaganira.heritier.iWarehouse.entity.ManualJournal;
import com.ntaganira.heritier.iWarehouse.enums.ManualJournalStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.repository
 * - File      : ManualJournalRepository.java
 * - Date      : 2026. 10. 09.
 * - User      : Hntaganira
 * - Desc      : Manual journals (ACC-05).
 * </pre>
 */
public interface ManualJournalRepository extends JpaRepository<ManualJournal, UUID>, JpaSpecificationExecutor<ManualJournal> {

    /** Locks it while it is decided or reversed, so two people never act on it at once. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from ManualJournal m where m.id = :id")
    Optional<ManualJournal> lockById(@Param("id") UUID id);

    long countByStatus(ManualJournalStatus status);
}
