package com.ntaganira.heritier.iWarehouse.repository;

import com.ntaganira.heritier.iWarehouse.entity.JournalRegisterCertification;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.repository
 * - File      : JournalRegisterCertificationRepository.java
 * - Date      : 2026. 10. 15.
 * - User      : Hntaganira
 * - Desc      : Certified journal registers (F-16), one per month.
 * </pre>
 */
public interface JournalRegisterCertificationRepository extends JpaRepository<JournalRegisterCertification, UUID> {

    Optional<JournalRegisterCertification> findByPeriodEnd(LocalDate periodEnd);

    Page<JournalRegisterCertification> findAllBy(Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from JournalRegisterCertification c where c.id = :id")
    Optional<JournalRegisterCertification> lockById(@Param("id") UUID id);
}
