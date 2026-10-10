package com.ntaganira.heritier.iWarehouse.repository;

import com.ntaganira.heritier.iWarehouse.entity.Driver;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.repository
 * - File      : DriverRepository.java
 * - Date      : 2026. 10. 10.
 * - User      : Hntaganira
 * - Desc      : Drivers (FLT-03) with their user account and usual vehicle.
 * </pre>
 */
public interface DriverRepository extends JpaRepository<Driver, UUID>, JpaSpecificationExecutor<Driver> {

    @EntityGraph(attributePaths = {"user", "defaultVehicle"})
    Page<Driver> findAll(Specification<Driver> spec, Pageable pageable);

    @EntityGraph(attributePaths = {"user", "defaultVehicle"})
    Optional<Driver> findDetailedById(UUID id);

    /** Departing takes the driver after the vehicle, so they never leave on two trips at once. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from Driver d where d.id = :id")
    Optional<Driver> lockById(@Param("id") UUID id);

    @EntityGraph(attributePaths = {"user"})
    Optional<Driver> findByUser_Id(Long userId);

    boolean existsByUser_Id(Long userId);

    boolean existsByNationalIdAndIdNot(String nationalId, UUID id);

    boolean existsByNationalId(String nationalId);

    boolean existsByLicenceNumberAndIdNot(String licenceNumber, UUID id);

    boolean existsByLicenceNumber(String licenceNumber);

    @EntityGraph(attributePaths = {"user", "defaultVehicle"})
    List<Driver> findByEnabledTrueOrderByUsername();
}
