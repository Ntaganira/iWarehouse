package com.ntaganira.heritier.iWarehouse.repository;

import com.ntaganira.heritier.iWarehouse.entity.SalesTarget;
import com.ntaganira.heritier.iWarehouse.enums.SalesTargetScope;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.Repository;
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
 * - File      : SalesTargetRepository.java
 * - Date      : 2026. 10. 16.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : Sales targets (T.07, T.09): a year's of one scope, those of the years a period covers.
 * </pre>
 */
public interface SalesTargetRepository extends JpaRepository<SalesTarget, UUID> {

    List<SalesTarget> findByTargetYearAndScope(int year, SalesTargetScope scope);

    List<SalesTarget> findByScopeAndTargetYearIn(SalesTargetScope scope, Collection<Integer> years);
}
