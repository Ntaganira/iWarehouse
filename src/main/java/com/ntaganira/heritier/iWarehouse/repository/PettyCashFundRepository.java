package com.ntaganira.heritier.iWarehouse.repository;

import com.ntaganira.heritier.iWarehouse.entity.PettyCashFund;
import com.ntaganira.heritier.iWarehouse.enums.PettyCashFundStatus;
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
 * - File      : PettyCashFundRepository.java
 * - Date      : 2026. 10. 16.
 * - User      : Hntaganira
 * - Desc      : Petty cash funds (CMP 07): all with their site, one locked while its cash moves, the one in use at a site.
 * </pre>
 */
public interface PettyCashFundRepository extends JpaRepository<PettyCashFund, UUID> {

    @EntityGraph(attributePaths = {"location"})
    List<PettyCashFund> findAllByOrderByStatusAscCodeAsc();

    @EntityGraph(attributePaths = {"location"})
    List<PettyCashFund> findByStatusOrderByCodeAsc(PettyCashFundStatus status);

    @EntityGraph(attributePaths = {"location"})
    Optional<PettyCashFund> findDetailedById(UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select f from PettyCashFund f where f.id = :id")
    Optional<PettyCashFund> lockById(@Param("id") UUID id);

    boolean existsByLocation_IdAndStatus(UUID locationId, PettyCashFundStatus status);

    boolean existsByCode(String code);
}
