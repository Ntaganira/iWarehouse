package com.ntaganira.heritier.iWarehouse.repository;

import com.ntaganira.heritier.iWarehouse.entity.AlertState;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.repository
 * - File      : AlertStateRepository.java
 * - Date      : 2026. 10. 10.
 * - User      : Hntaganira
 * - Desc      : The conditions the alerts watch (RPT-06), by key.
 * </pre>
 */
public interface AlertStateRepository extends JpaRepository<AlertState, String> {

    /** The raised ones whose key starts with a prefix ("LOW_STOCK:"). */
    List<AlertState> findByKeyStartingWithAndClearedAtIsNull(String prefix);
}
