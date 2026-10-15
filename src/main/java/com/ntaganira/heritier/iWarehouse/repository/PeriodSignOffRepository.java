package com.ntaganira.heritier.iWarehouse.repository;

import com.ntaganira.heritier.iWarehouse.entity.PeriodSignOff;
import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.repository
 * - File      : PeriodSignOffRepository.java
 * - Date      : 2026. 10. 15.
 * - User      : Hntaganira
 * - Desc      : Sign-offs of closed months (BP 4.2). Append-only: save and finders only.
 * </pre>
 */
public interface PeriodSignOffRepository extends Repository<PeriodSignOff, UUID> {

    PeriodSignOff save(PeriodSignOff signOff);

    List<PeriodSignOff> findByPeriodIdOrderByCreatedAtAsc(UUID periodId);
}
