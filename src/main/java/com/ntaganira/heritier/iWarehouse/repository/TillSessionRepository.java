package com.ntaganira.heritier.iWarehouse.repository;

import com.ntaganira.heritier.iWarehouse.entity.TillSession;
import com.ntaganira.heritier.iWarehouse.enums.TillStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
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
 * - File      : TillSessionRepository.java
 * - Date      : 2026. 10. 09.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : Till sessions (POS-10).
 * </pre>
 */
public interface TillSessionRepository extends JpaRepository<TillSession, UUID>, JpaSpecificationExecutor<TillSession> {

    Optional<TillSession> findByCashierIdAndStatus(Long cashierId, TillStatus status);

    /** Selling and closing take this lock first, so a till closes once and never while a sale is paid. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from TillSession t where t.id = :id")
    Optional<TillSession> lockById(@Param("id") UUID id);

    /** Tills in a state (the owner dashboard counts the open ones). */
    long countByStatus(TillStatus status);

    /** Tills closed between two moments (the daily close). */
    List<TillSession> findByClosedAtBetweenOrderByClosedAtAsc(LocalDateTime from, LocalDateTime to);

    /** Tills still open that were opened before a moment (a day is not closed while one of its tills is open). */
    List<TillSession> findByStatusAndOpenedAtBeforeOrderByOpenedAtAsc(TillStatus status, LocalDateTime before);

    /** Closed tills whose cash is not banked yet, oldest first (F-13). */
    List<TillSession> findByStatusAndDepositIdIsNullOrderByClosedAtAsc(TillStatus status);

    /** The tills a deposit banked. */
    List<TillSession> findByDepositIdOrderByClosedAtAsc(UUID depositId);

    /** Banking takes the tills first, so a till is banked once. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from TillSession t where t.id in :ids order by t.id")
    List<TillSession> lockAllById(@Param("ids") Collection<UUID> ids);
}
