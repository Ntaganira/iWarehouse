package com.ntaganira.heritier.iWarehouse.repository;

import com.ntaganira.heritier.iWarehouse.entity.AuditCaseLine;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.repository
 * - File      : AuditCaseLineRepository.java
 * - Date      : 2026. 10. 11.
 * - User      : Hntaganira
 * - Desc      : The units of audit cases (FLT-10).
 * </pre>
 */
public interface AuditCaseLineRepository extends JpaRepository<AuditCaseLine, UUID> {

    /** A missing unit not settled yet, with its case: a sale of it synced late settles it (SOLD). */
    @Query("select l from AuditCaseLine l join fetch l.auditCase where l.stockUnitId = :unitId and l.kind = 'MISSING' and l.outcome is null")
    Optional<AuditCaseLine> findOpenMissing(@Param("unitId") UUID unitId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from AuditCaseLine l where l.id = :id")
    Optional<AuditCaseLine> lockById(@Param("id") UUID id);
}
