package com.ntaganira.heritier.iWarehouse.repository;

import com.ntaganira.heritier.iWarehouse.entity.Territory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.repository
 * - File      : TerritoryRepository.java
 * - Date      : 2026. 10. 15.
 * - User      : Hntaganira
 * - Desc      : Sales territories (BP 6), by code.
 * </pre>
 */
public interface TerritoryRepository extends JpaRepository<Territory, UUID> {

    List<Territory> findAllByOrderByCodeAsc();

    List<Territory> findByEnabledTrueOrderByCodeAsc();

    Optional<Territory> findByCode(String code);
}
