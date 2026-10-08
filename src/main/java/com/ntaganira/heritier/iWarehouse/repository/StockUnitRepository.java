package com.ntaganira.heritier.iWarehouse.repository;

import com.ntaganira.heritier.iWarehouse.entity.StockUnit;
import com.ntaganira.heritier.iWarehouse.enums.StockStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StockUnitRepository extends JpaRepository<StockUnit, UUID>, JpaSpecificationExecutor<StockUnit> {

    @EntityGraph(attributePaths = {"product", "location", "crateBatch"})
    Page<StockUnit> findAll(Specification<StockUnit> spec, Pageable pageable);

    @EntityGraph(attributePaths = {"product", "location", "crateBatch", "crateBatch.goodsReceipt",
            "crateBatch.goodsReceipt.purchaseOrder", "crateBatch.goodsReceipt.purchaseOrder.supplier"})
    Optional<StockUnit> findDetailedById(UUID id);

    Optional<StockUnit> findByCodeIgnoreCase(String code);

    @EntityGraph(attributePaths = {"product", "location", "crateBatch"})
    List<StockUnit> findByCrateBatch_GoodsReceipt_IdOrderByCode(UUID receiptId);

    @EntityGraph(attributePaths = {"product", "location", "crateBatch"})
    List<StockUnit> findByCrateBatch_IdOrderByCode(UUID crateId);

    /** Units of some crates by code (landed cost allocation). */
    List<StockUnit> findByCrateBatch_IdInOrderByCode(Collection<UUID> crateIds);

    long countByLocation_IdAndStatusIn(UUID locationId, Collection<StockStatus> statuses);

    long countByProduct_IdAndStatusIn(UUID productId, Collection<StockStatus> statuses);

    /** m² of a product still held (moving average cost, PRC-05). */
    @Query("select coalesce(sum(u.areaM2), 0) from StockUnit u where u.product.id = :productId and u.status in :statuses")
    BigDecimal sumArea(@Param("productId") UUID productId, @Param("statuses") Collection<StockStatus> statuses);

    /** Rows of (location id, pieces, kg) of units held, per location (rack limits, MD-03). */
    @Query("select u.location.id, count(u), coalesce(sum(u.weightKg), 0) from StockUnit u"
            + " where u.status in :statuses and u.location is not null group by u.location.id")
    List<Object[]> loadByLocation(@Param("statuses") Collection<StockStatus> statuses);
}
