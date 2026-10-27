package com.ntaganira.heritier.iWarehouse.repository;

import com.ntaganira.heritier.iWarehouse.entity.CashForecastItem;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.repository
 * - File      : CashForecastItemRepository.java
 * - Date      : 2026. 10. 16.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : Planned items of the cash-flow forecast (P.11): the active ones, one locked.
 * </pre>
 */
public interface CashForecastItemRepository extends JpaRepository<CashForecastItem, UUID> {

    List<CashForecastItem> findByStatusOrderByExpectedOnAscDescriptionAsc(String status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from CashForecastItem i where i.id = :id")
    Optional<CashForecastItem> lockById(@Param("id") UUID id);
}
