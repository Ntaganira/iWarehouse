package com.ntaganira.heritier.iWarehouse.repository;

import com.ntaganira.heritier.iWarehouse.entity.ApiDevice;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.repository
 * - File      : ApiDeviceRepository.java
 * - Date      : 2026. 10. 10.
 * - User      : Hntaganira
 * - Desc      : Phones signed in to the mobile POS (NFR-10), found by the hash of their token.
 * </pre>
 */
public interface ApiDeviceRepository extends JpaRepository<ApiDevice, UUID>, JpaSpecificationExecutor<ApiDevice> {

    @EntityGraph(attributePaths = {"user"})
    Page<ApiDevice> findAll(Specification<ApiDevice> spec, Pageable pageable);

    @EntityGraph(attributePaths = {"user"})
    Optional<ApiDevice> findByTokenHash(String tokenHash);

    /** The live token of a user on a phone (one at most, uk_api_devices_live). */
    Optional<ApiDevice> findByUser_IdAndDeviceKeyAndRevokedAtIsNull(Long userId, String deviceKey);

    @EntityGraph(attributePaths = {"user"})
    Optional<ApiDevice> findDetailedById(UUID id);

    long countByRevokedAtIsNull();
}
