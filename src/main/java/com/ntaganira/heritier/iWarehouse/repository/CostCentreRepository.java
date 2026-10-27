package com.ntaganira.heritier.iWarehouse.repository;

import com.ntaganira.heritier.iWarehouse.entity.CostCentre;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;


/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.repository
 * - File      : CostCentreRepository.java
 * - Date      : 2026. 10. 16.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : Cost centres (F-15 A), looked up by their code (upper case).
 * </pre>
 */
public interface CostCentreRepository extends JpaRepository<CostCentre, UUID> {

    List<CostCentre> findAllByOrderByCodeAsc();

    List<CostCentre> findByEnabledTrueOrderByCodeAsc();

    Optional<CostCentre> findByCode(String code);
}
