package com.ntaganira.heritier.iWarehouse.repository;

import com.ntaganira.heritier.iWarehouse.entity.TripCountLine;
import org.springframework.data.repository.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.repository
 * - File      : TripCountLineRepository.java
 * - Date      : 2026. 10. 15.
 * - User      : Hntaganira
 * - Desc      : The units of van counts. Append-only: save and finders only.
 * </pre>
 */
public interface TripCountLineRepository extends Repository<TripCountLine, UUID> {

    TripCountLine save(TripCountLine line);

    List<TripCountLine> findByCountIdOrderByOutcomeAscUnitCodeAsc(UUID countId);

    List<TripCountLine> findByCountIdIn(Collection<UUID> countIds);
}
