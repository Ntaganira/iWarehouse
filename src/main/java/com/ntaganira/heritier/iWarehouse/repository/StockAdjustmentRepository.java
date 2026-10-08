package com.ntaganira.heritier.iWarehouse.repository;

import com.ntaganira.heritier.iWarehouse.entity.StockAdjustment;
import com.ntaganira.heritier.iWarehouse.enums.AdjustmentStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface StockAdjustmentRepository extends JpaRepository<StockAdjustment, UUID>, JpaSpecificationExecutor<StockAdjustment> {

    Page<StockAdjustment> findAll(Specification<StockAdjustment> spec, Pageable pageable);

    /** An adjustment with its lines, their products and locations. */
    @EntityGraph(attributePaths = {"lines", "lines.product", "lines.location"})
    Optional<StockAdjustment> findDetailedById(UUID id);

    /** Approving, rejecting and withdrawing take this lock first, so an adjustment is decided once. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from StockAdjustment a where a.id = :id")
    Optional<StockAdjustment> lockById(@Param("id") UUID id);

    long countByStatus(AdjustmentStatus status);
}
