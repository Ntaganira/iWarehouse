package com.ntaganira.heritier.iWarehouse.repository;

import com.ntaganira.heritier.iWarehouse.entity.Territory;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.repository
 * - File      : TerritoryRepository.java
 * - Date      : 2026. 10. 15.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : Sales territories (BP 6), by code.
 * </pre>
 */
public interface TerritoryRepository extends JpaRepository<Territory, UUID> {

    List<Territory> findAllByOrderByCodeAsc();

    @EntityGraph(attributePaths = {"province", "district", "sector", "cell", "village"})
    List<Territory> findAllWithAreaByOrderByCodeAsc();

    @EntityGraph(attributePaths = {"province", "district", "sector", "cell", "village"})
    @Query("select t from Territory t where t.id = :id")
    Optional<Territory> findWithAreaById(@Param("id") UUID id);

    List<Territory> findByEnabledTrueOrderByCodeAsc();

    Optional<Territory> findByCode(String code);

    Optional<Territory> findByProvinceId(UUID provinceId);

    Optional<Territory> findByDistrictId(UUID districtId);

    Optional<Territory> findBySectorId(UUID sectorId);

    Optional<Territory> findByCellId(UUID cellId);

    Optional<Territory> findByVillageId(UUID villageId);
}
