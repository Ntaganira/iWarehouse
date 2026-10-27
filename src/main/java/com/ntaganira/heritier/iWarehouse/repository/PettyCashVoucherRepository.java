package com.ntaganira.heritier.iWarehouse.repository;

import com.ntaganira.heritier.iWarehouse.entity.PettyCashVoucher;
import com.ntaganira.heritier.iWarehouse.enums.PettyCashVoucherStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;


/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.repository
 * - File      : PettyCashVoucherRepository.java
 * - Date      : 2026. 10. 16.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : Petty cash vouchers (CMP 07, F-13 D): pages with their fund, site and cost centre, one locked, those waiting.
 * </pre>
 */
public interface PettyCashVoucherRepository extends JpaRepository<PettyCashVoucher, UUID>, JpaSpecificationExecutor<PettyCashVoucher> {

    @Override
    @EntityGraph(attributePaths = {"fund", "fund.location", "costCentre"})
    Page<PettyCashVoucher> findAll(Specification<PettyCashVoucher> spec, Pageable pageable);

    @EntityGraph(attributePaths = {"fund", "fund.location", "costCentre"})
    Optional<PettyCashVoucher> findDetailedById(UUID id);

    @EntityGraph(attributePaths = {"costCentre"})
    Page<PettyCashVoucher> findByFund_IdOrderByVoucherDateDescNumberDesc(UUID fundId, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from PettyCashVoucher v where v.id = :id")
    Optional<PettyCashVoucher> lockById(@Param("id") UUID id);

    long countByStatus(PettyCashVoucherStatus status);

    long countByFund_IdAndStatus(UUID fundId, PettyCashVoucherStatus status);
}
