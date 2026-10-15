package com.ntaganira.heritier.iWarehouse.repository;

import com.ntaganira.heritier.iWarehouse.entity.BalanceConfirmation;
import com.ntaganira.heritier.iWarehouse.enums.ConfirmationStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
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
 * - File      : BalanceConfirmationRepository.java
 * - Date      : 2026. 10. 15.
 * - User      : Hntaganira
 * - Desc      : Balance confirmations sent to customers (SDP 10).
 * </pre>
 */
public interface BalanceConfirmationRepository extends JpaRepository<BalanceConfirmation, UUID> {

    @EntityGraph(attributePaths = {"customer"})
    Optional<BalanceConfirmation> findDetailedById(UUID id);

    @EntityGraph(attributePaths = {"customer"})
    Page<BalanceConfirmation> findAllBy(Pageable pageable);

    @EntityGraph(attributePaths = {"customer"})
    Page<BalanceConfirmation> findByStatus(ConfirmationStatus status, Pageable pageable);

    @EntityGraph(attributePaths = {"customer"})
    Page<BalanceConfirmation> findByCustomer_Id(UUID customerId, Pageable pageable);

    boolean existsByCustomer_IdAndStatus(UUID customerId, ConfirmationStatus status);

    long countByStatus(ConfirmationStatus status);

    /** Recording the reply takes it first. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from BalanceConfirmation b where b.id = :id")
    Optional<BalanceConfirmation> lockById(@Param("id") UUID id);
}
