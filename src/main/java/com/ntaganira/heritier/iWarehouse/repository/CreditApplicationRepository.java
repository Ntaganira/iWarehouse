package com.ntaganira.heritier.iWarehouse.repository;

import com.ntaganira.heritier.iWarehouse.entity.CreditApplication;
import com.ntaganira.heritier.iWarehouse.enums.CreditApplicationStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.repository
 * - File      : CreditApplicationRepository.java
 * - Date      : 2026. 10. 15.
 * - User      : Hntaganira
 * - Desc      : Credit applications (F-02): the list, one with its customer and price lists, those waiting, a customer's.
 * </pre>
 */
public interface CreditApplicationRepository extends JpaRepository<CreditApplication, UUID>, JpaSpecificationExecutor<CreditApplication> {

    @EntityGraph(attributePaths = {"customer"})
    Page<CreditApplication> findAll(Specification<CreditApplication> spec, Pageable pageable);

    @EntityGraph(attributePaths = {"customer", "priceList", "previousPriceList"})
    Optional<CreditApplication> findDetailedById(UUID id);

    long countByStatusIn(Collection<CreditApplicationStatus> statuses);

    /** Deciding takes this lock, so an application is decided once. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from CreditApplication a where a.id = :id")
    Optional<CreditApplication> lockById(@Param("id") UUID id);

    /** A customer's applications still waiting (one at a time). */
    List<CreditApplication> findByCustomer_IdAndStatusIn(UUID customerId, Collection<CreditApplicationStatus> statuses);

    /** A customer's applications, newest first (their page). */
    @EntityGraph(attributePaths = {"priceList"})
    List<CreditApplication> findByCustomer_IdOrderByRequestedAtDesc(UUID customerId);

    /** Those decided in a period (the exceptions report). */
    @EntityGraph(attributePaths = {"customer"})
    List<CreditApplication> findByDecidedAtBetweenOrderByDecidedAtAsc(LocalDateTime from, LocalDateTime to);
}
