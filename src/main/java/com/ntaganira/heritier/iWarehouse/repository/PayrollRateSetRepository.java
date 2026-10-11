package com.ntaganira.heritier.iWarehouse.repository;

import com.ntaganira.heritier.iWarehouse.entity.PayrollRateSet;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.repository
 * - File      : PayrollRateSetRepository.java
 * - Date      : 2026. 10. 11.
 * - User      : Hntaganira
 * - Desc      : The payroll rates in force from each date.
 * </pre>
 */
public interface PayrollRateSetRepository extends JpaRepository<PayrollRateSet, UUID> {

    List<PayrollRateSet> findAllByOrderByEffectiveFromDesc();

    /** The set in force on a day: the latest from that day or before. */
    Optional<PayrollRateSet> findFirstByEffectiveFromLessThanEqualOrderByEffectiveFromDesc(LocalDate day);

    boolean existsByEffectiveFrom(LocalDate effectiveFrom);

    boolean existsByEffectiveFromAndIdNot(LocalDate effectiveFrom, UUID id);
}
