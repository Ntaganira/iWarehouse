package com.ntaganira.heritier.iWarehouse.repository;

import com.ntaganira.heritier.iWarehouse.entity.PettyCashCount;
import com.ntaganira.heritier.iWarehouse.enums.PettyCashCountKind;
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
 * - File      : PettyCashCountRepository.java
 * - Date      : 2026. 10. 16.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : Counts of petty cash funds (append-only: save and finders only): a fund's counts, its latest of a kind, the days counted.
 * </pre>
 */
public interface PettyCashCountRepository extends Repository<PettyCashCount, UUID> {

    PettyCashCount save(PettyCashCount count);

    Page<PettyCashCount> findByFund_IdOrderByCountedAtDesc(UUID fundId, Pageable pageable);

    Optional<PettyCashCount> findFirstByFund_IdAndKindOrderByCountedAtDesc(UUID fundId, PettyCashCountKind kind);

    boolean existsByFund_IdAndKindAndCountedOnBetween(UUID fundId, PettyCashCountKind kind, LocalDate from, LocalDate to);

    @Query("select distinct c.countedOn from PettyCashCount c where c.fund.id = :fundId and c.kind = :kind and c.countedOn between :from and :to")
    List<LocalDate> countedDays(@Param("fundId") UUID fundId, @Param("kind") PettyCashCountKind kind, @Param("from") LocalDate from,
                                @Param("to") LocalDate to);
}
