package com.ntaganira.heritier.iWarehouse.repository;

import com.ntaganira.heritier.iWarehouse.entity.StockCountScan;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.repository
 * - File      : StockCountScanRepository.java
 * - Date      : 2026. 10. 08.
 * - User      : Hntaganira
 * - Desc      : Labels scanned during stock counts (INV-08).
 * </pre>
 */
public interface StockCountScanRepository extends JpaRepository<StockCountScan, UUID> {
}
