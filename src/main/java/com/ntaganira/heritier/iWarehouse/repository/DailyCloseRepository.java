package com.ntaganira.heritier.iWarehouse.repository;

import com.ntaganira.heritier.iWarehouse.entity.DailyClose;
import com.ntaganira.heritier.iWarehouse.enums.DailyCloseStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.repository
 * - File      : DailyCloseRepository.java
 * - Date      : 2026. 10. 15.
 * - User      : Hntaganira
 * - Desc      : Daily closes (F-12), one per business day.
 * </pre>
 */
public interface DailyCloseRepository extends JpaRepository<DailyClose, UUID> {

    Optional<DailyClose> findByBusinessDay(LocalDate businessDay);

    Page<DailyClose> findByStatus(DailyCloseStatus status, Pageable pageable);

    /** Signed closes not tested yet (the Internal Controller's weekly test). */
    Page<DailyClose> findByStatusAndTestedAtIsNull(DailyCloseStatus status, Pageable pageable);

    /** The days of a period that have a close. */
    @Query("select d.businessDay from DailyClose d where d.businessDay in :days")
    List<LocalDate> closedOf(@Param("days") Collection<LocalDate> days);

    long countByStatus(DailyCloseStatus status);

    /** Preparing again, signing and testing take it one at a time. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from DailyClose d where d.id = :id")
    Optional<DailyClose> lockById(@Param("id") UUID id);
}
