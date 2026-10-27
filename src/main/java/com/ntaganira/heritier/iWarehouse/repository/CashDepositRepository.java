package com.ntaganira.heritier.iWarehouse.repository;

import com.ntaganira.heritier.iWarehouse.entity.CashDeposit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.repository
 * - File      : CashDepositRepository.java
 * - Date      : 2026. 10. 15.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : Takings banked with their slip (F-13).
 * </pre>
 */
public interface CashDepositRepository extends JpaRepository<CashDeposit, UUID> {

    @EntityGraph(attributePaths = {"account"})
    Optional<CashDeposit> findDetailedById(UUID id);

    @EntityGraph(attributePaths = {"account"})
    Page<CashDeposit> findAllBy(Pageable pageable);

    /** Deposits of a day (the daily close). */
    List<CashDeposit> findByDepositDateOrderByNumber(LocalDate depositDate);

    boolean existsByAccount_IdAndSlipNumber(UUID accountId, String slipNumber);
}
