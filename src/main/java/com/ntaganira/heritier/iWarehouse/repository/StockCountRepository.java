package com.ntaganira.heritier.iWarehouse.repository;

import com.ntaganira.heritier.iWarehouse.entity.StockCount;
import com.ntaganira.heritier.iWarehouse.enums.StockCountStatus;
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

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.repository
 * - File      : StockCountRepository.java
 * - Date      : 2026. 10. 08.
 * - User      : Hntaganira
 * - Desc      : Stock counts (INV-08), and the units open counts hold (INV-05).
 * </pre>
 */
public interface StockCountRepository extends JpaRepository<StockCount, UUID>, JpaSpecificationExecutor<StockCount> {

    @EntityGraph(attributePaths = {"location", "product"})
    Page<StockCount> findAll(Specification<StockCount> spec, Pageable pageable);

    /** A count with its place, glass, places covered and scans. */
    @EntityGraph(attributePaths = {"location", "product", "places", "scans"})
    Optional<StockCount> findDetailedById(UUID id);

    /** Scanning, closing and cancelling take this lock first, so a count closes once. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from StockCount c where c.id = :id")
    Optional<StockCount> lockById(@Param("id") UUID id);

    /** Units an open count holds: (unit id, count number) for those on its places, of its glass if it has one. */
    @Query(value = "SELECT u.id, c.number FROM stock_units u"
            + " JOIN stock_count_places p ON p.location_id = u.location_id"
            + " JOIN stock_counts c ON c.id = p.count_id"
            + " WHERE c.status = 'OPEN' AND (c.product_id IS NULL OR c.product_id = u.product_id) AND u.id IN (:unitIds)",
            nativeQuery = true)
    List<Object[]> findHolds(@Param("unitIds") Collection<UUID> unitIds);

    /** Open counts covering any of these places (two open counts never cover the same place). */
    @Query(value = "SELECT DISTINCT c.number FROM stock_counts c JOIN stock_count_places p ON p.count_id = c.id"
            + " WHERE c.status = 'OPEN' AND p.location_id IN (:locationIds)", nativeQuery = true)
    List<String> findOpenCovering(@Param("locationIds") Collection<UUID> locationIds);

    long countByStatus(StockCountStatus status);
}
