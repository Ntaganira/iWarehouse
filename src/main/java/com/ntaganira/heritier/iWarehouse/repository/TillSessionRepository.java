package com.ntaganira.heritier.iWarehouse.repository;

import com.ntaganira.heritier.iWarehouse.entity.TillSession;
import com.ntaganira.heritier.iWarehouse.enums.TillStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.repository
 * - File      : TillSessionRepository.java
 * - Date      : 2026. 10. 09.
 * - User      : Hntaganira
 * - Desc      : Till sessions (POS-10).
 * </pre>
 */
public interface TillSessionRepository extends JpaRepository<TillSession, UUID>, JpaSpecificationExecutor<TillSession> {

    Optional<TillSession> findByCashierIdAndStatus(Long cashierId, TillStatus status);

    /** Selling and closing take this lock first, so a till closes once and never while a sale is paid. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from TillSession t where t.id = :id")
    Optional<TillSession> lockById(@Param("id") UUID id);
}
