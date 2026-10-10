package com.ntaganira.heritier.iWarehouse.repository;

import com.ntaganira.heritier.iWarehouse.entity.EbmDevice;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.repository
 * - File      : EbmDeviceRepository.java
 * - Date      : 2026. 10. 10.
 * - User      : Hntaganira
 * - Desc      : EBM device initialisations, one per TIN, branch, device serial and mode.
 * </pre>
 */
public interface EbmDeviceRepository extends JpaRepository<EbmDevice, UUID> {

    Optional<EbmDevice> findByTinAndBranchIdAndDeviceSerialAndSimulated(String tin, String branchId, String deviceSerial, boolean simulated);
}
