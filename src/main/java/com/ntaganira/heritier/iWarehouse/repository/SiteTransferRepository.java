package com.ntaganira.heritier.iWarehouse.repository;

import com.ntaganira.heritier.iWarehouse.entity.SiteTransfer;
import com.ntaganira.heritier.iWarehouse.enums.SiteTransferStatus;
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
 * - File      : SiteTransferRepository.java
 * - Date      : 2026. 10. 15.
 * - User      : Hntaganira
 * - Desc      : Transfers between sites (IMP 11): the list, one with its sites, those open, those overdue on arrival.
 * </pre>
 */
public interface SiteTransferRepository extends JpaRepository<SiteTransfer, UUID>, JpaSpecificationExecutor<SiteTransfer> {

    @EntityGraph(attributePaths = {"fromSite", "toSite"})
    Page<SiteTransfer> findAll(Specification<SiteTransfer> spec, Pageable pageable);

    @EntityGraph(attributePaths = {"fromSite", "toSite"})
    Optional<SiteTransfer> findDetailedById(UUID id);

    long countByStatusIn(Collection<SiteTransferStatus> statuses);

    /** Dispatching, receiving and deciding take this lock, so a transfer moves once. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from SiteTransfer t where t.id = :id")
    Optional<SiteTransfer> lockById(@Param("id") UUID id);

    /** Transfers still in transit that left before a moment (labels not received in time are a difference). */
    @EntityGraph(attributePaths = {"fromSite", "toSite"})
    List<SiteTransfer> findByStatusAndDispatchedAtBefore(SiteTransferStatus status, LocalDateTime before);

    /** Those decided in a period (the exceptions report). */
    @EntityGraph(attributePaths = {"fromSite", "toSite"})
    List<SiteTransfer> findByDecidedAtBetweenOrderByDecidedAtAsc(LocalDateTime from, LocalDateTime to);
}
