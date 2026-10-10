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

    @EntityGraph(attributePaths = {"product", "location", "reservedCustomer", "crateBatch", "crateBatch.goodsReceipt",
            "crateBatch.goodsReceipt.purchaseOrder", "crateBatch.goodsReceipt.purchaseOrder.supplier"})
    Optional<StockUnit> findDetailedById(UUID id);

    /** A unit by its label code, exactly as stored (upper case): the unique index finds it (NFR-02). */
    Optional<StockUnit> findByCode(String code);

    @EntityGraph(attributePaths = {"product", "location", "crateBatch"})
    List<StockUnit> findByCrateBatch_GoodsReceipt_IdOrderByCode(UUID receiptId);

    @EntityGraph(attributePaths = {"product", "location", "crateBatch"})
    List<StockUnit> findByCrateBatch_IdOrderByCode(UUID crateId);

    /** Units of some crates by code (landed cost allocation). */
    List<StockUnit> findByCrateBatch_IdInOrderByCode(Collection<UUID> crateIds);

    /** Units by id, with product and location (documents listing units). */
    @EntityGraph(attributePaths = {"product", "location"})
    List<StockUnit> findByIdIn(Collection<UUID> ids);

    /** Units by label code (scanned lists), with product and location. */
    @EntityGraph(attributePaths = {"product", "location"})
    List<StockUnit> findByCodeIn(Collection<String> codes);

    /** Units on these places in these states, with product and location (a stock count's expected units, INV-08). */
    @EntityGraph(attributePaths = {"product", "location"})
    List<StockUnit> findByLocation_IdInAndStatusIn(Collection<UUID> locationIds, Collection<StockStatus> statuses);

    /** Rows of (product id, location id, status, pieces, m²) of units in some states (INV-09, INV-10). */
    @Query("select u.product.id, u.location.id, u.status, count(u), coalesce(sum(u.areaM2), 0) from StockUnit u"
            + " where u.status in :statuses group by u.product.id, u.location.id, u.status")
    List<Object[]> summarize(@Param("statuses") Collection<StockStatus> statuses);

    /** Units held as (id, code, product id, kind, width, height, m², location id, status, created at), for the stock reports (RPT-02). */
    @Query("select u.id, u.code, u.product.id, u.kind, u.widthMm, u.heightMm, u.areaM2, l.id, u.status, u.createdAt"
            + " from StockUnit u left join u.location l where u.status in :statuses")
    List<Object[]> held(@Param("statuses") Collection<StockStatus> statuses);

    /** Units cut from a unit (PRD-03), by code. */
    @EntityGraph(attributePaths = {"product", "location", "crateBatch"})
    List<StockUnit> findByParentUnitIdOrderByCode(UUID parentUnitId);

    long countByLocation_IdAndStatusIn(UUID locationId, Collection<StockStatus> statuses);

    long countByProduct_IdAndStatusIn(UUID productId, Collection<StockStatus> statuses);

    /** m² of a product still held (moving average cost, PRC-05). */
    @Query("select coalesce(sum(u.areaM2), 0) from StockUnit u where u.product.id = :productId and u.status in :statuses")
    BigDecimal sumArea(@Param("productId") UUID productId, @Param("statuses") Collection<StockStatus> statuses);

    /** Rows of (location id, pieces, kg) of units held, per location (rack limits, MD-03). */
    @Query("select u.location.id, count(u), coalesce(sum(u.weightKg), 0) from StockUnit u"
            + " where u.status in :statuses and u.location is not null group by u.location.id")
    List<Object[]> loadByLocation(@Param("statuses") Collection<StockStatus> statuses);

    /** Units of a glass of exactly this size, either way round, in a state, by label (a quotation's whole sheets, POS-03). */
    @EntityGraph(attributePaths = {"product", "location"})
    @Query("select u from StockUnit u where u.product.id = :productId and u.status = :status"
            + " and ((u.widthMm = :w and u.heightMm = :h) or (u.widthMm = :h and u.heightMm = :w)) order by u.code")
    List<StockUnit> findOfSize(@Param("productId") UUID productId, @Param("w") int widthMm, @Param("h") int heightMm,
                               @Param("status") StockStatus status);
}
