package com.ntaganira.heritier.iWarehouse.repository;

import com.ntaganira.heritier.iWarehouse.entity.PriceChange;
import com.ntaganira.heritier.iWarehouse.enums.PriceChangeStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.repository
 * - File      : PriceChangeRepository.java
 * - Date      : 2026. 10. 15.
 * - User      : Hntaganira
 * - Desc      : Price changes (SDP 5): the list, one with its price list, those waiting, a list's waiting one.
 * </pre>
 */
public interface PriceChangeRepository extends JpaRepository<PriceChange, UUID>, JpaSpecificationExecutor<PriceChange> {

    @EntityGraph(attributePaths = {"priceList"})
    Page<PriceChange> findAll(Specification<PriceChange> spec, Pageable pageable);

    @EntityGraph(attributePaths = {"priceList"})
    Optional<PriceChange> findDetailedById(UUID id);

    long countByStatus(PriceChangeStatus status);

    /** The change waiting on a price list (one at a time). */
    Optional<PriceChange> findByPriceList_IdAndStatus(UUID priceListId, PriceChangeStatus status);

    /** Deciding takes this lock, so a change is decided once. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from PriceChange c where c.id = :id")
    Optional<PriceChange> lockById(@Param("id") UUID id);

    /** Those decided in a period (the exceptions report). */
    @EntityGraph(attributePaths = {"priceList"})
    List<PriceChange> findByDecidedAtBetweenOrderByDecidedAtAsc(LocalDateTime from, LocalDateTime to);
}
