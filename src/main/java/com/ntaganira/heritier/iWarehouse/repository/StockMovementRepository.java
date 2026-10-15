package com.ntaganira.heritier.iWarehouse.repository;

import com.ntaganira.heritier.iWarehouse.entity.StockMovement;
import com.ntaganira.heritier.iWarehouse.enums.MovementType;
import com.ntaganira.heritier.iWarehouse.enums.StockStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** Append-only: save and read, no update or delete methods on purpose (INV-04). */
public interface StockMovementRepository extends Repository<StockMovement, UUID> {

    StockMovement save(StockMovement movement);

    List<StockMovement> findByStockUnitIdOrderByMovedAtAsc(UUID stockUnitId);

    /** Rows of (unit id, place) of some units' movements of a type: where sold sheets left from (a delivery note's store). */
    @Query("select m.stockUnitId, m.fromLocationId from StockMovement m where m.stockUnitId in :ids and m.type = :type")
    List<Object[]> fromOf(@Param("ids") Collection<UUID> ids, @Param("type") MovementType type);

    /**
     * The movements of these types since a moment, one row per document and type: (ref type, ref id, ref number, type,
     * first moved at, first username, first user id). Transaction Tickets are raised from them (decision D6).
     */
    @Query("select m.refType, m.refId, min(m.refNumber), m.type, min(m.movedAt), min(m.username), min(m.userId) from StockMovement m"
            + " where m.movedAt >= :since and m.refId is not null and m.type in :types group by m.refType, m.refId, m.type")
    List<Object[]> documentsSince(@Param("since") LocalDateTime since, @Param("types") Collection<MovementType> types);

    /** A document's units moved by these types: (pieces, m², cost). */
    @Query("select count(distinct m.stockUnitId), coalesce(sum(u.areaM2), 0), coalesce(sum(u.unitCost), 0) from StockMovement m, StockUnit u"
            + " where u.id = m.stockUnitId and m.refId = :refId and m.type in :types")
    List<Object[]> totalsOf(@Param("refId") UUID refId, @Param("types") Collection<MovementType> types);

    /** A document's movements of these types with their units, by label (a Transaction Ticket's page). */
    @Query("select m, u from StockMovement m, StockUnit u where u.id = m.stockUnitId and m.refId = :refId and m.type in :types"
            + " order by u.code, m.movedAt")
    List<Object[]> ofDocument(@Param("refId") UUID refId, @Param("types") Collection<MovementType> types);

    /** A day's movements, newest first (the daily review, IMP 12). */
    Page<StockMovement> findByMovedAtBetweenOrderByMovedAtDesc(LocalDateTime from, LocalDateTime to, Pageable pageable);

    Page<StockMovement> findByMovedAtBetweenAndTypeOrderByMovedAtDesc(LocalDateTime from, LocalDateTime to, MovementType type, Pageable pageable);

    /** Movements per type in a period: (type, count, m², cost). */
    @Query("select m.type, count(m), coalesce(sum(u.areaM2), 0), coalesce(sum(u.unitCost), 0) from StockMovement m, StockUnit u"
            + " where u.id = m.stockUnitId and m.movedAt between :from and :to group by m.type")
    List<Object[]> byTypeBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** Units that left stock in a period for these statuses: (count, cost) (write-offs, breakage, losses). */
    @Query("select count(m), coalesce(sum(u.unitCost), 0) from StockMovement m, StockUnit u where u.id = m.stockUnitId"
            + " and m.movedAt between :from and :to and m.toStatus in :statuses and m.fromStatus not in :statuses")
    List<Object[]> leftForBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
                                  @Param("statuses") Collection<StockStatus> statuses);

    /** A glass's movements up to a moment, oldest first, with their units (the stock card, IMP 7.1). */
    @Query("select m, u from StockMovement m, StockUnit u where u.id = m.stockUnitId and u.product.id = :productId"
            + " and m.movedAt < :to order by m.movedAt, m.id")
    List<Object[]> ofProductUntil(@Param("productId") UUID productId, @Param("to") LocalDateTime to);

    /** Per glass, (product id, last movement of the type, m² moved since the day): what each glass sold (RPT-02). */
    @Query("select u.product.id, max(m.movedAt), coalesce(sum(case when m.movedAt >= :since then u.areaM2 else 0 end), 0)"
            + " from StockMovement m, StockUnit u where u.id = m.stockUnitId and m.type = :type group by u.product.id")
    List<Object[]> movedByProduct(@Param("type") MovementType type, @Param("since") LocalDateTime since);
}
