package com.ntaganira.heritier.iWarehouse.repository;

import com.ntaganira.heritier.iWarehouse.entity.StockTransfer;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StockTransferRepository extends JpaRepository<StockTransfer, UUID>, JpaSpecificationExecutor<StockTransfer> {

    @EntityGraph(attributePaths = {"toLocation"})
    Page<StockTransfer> findAll(Specification<StockTransfer> spec, Pageable pageable);

    /** A transfer with its lines and destination. */
    @EntityGraph(attributePaths = {"lines", "toLocation"})
    Optional<StockTransfer> findDetailedById(UUID id);

    /** Rows of (transfer id, units) for a page of transfers. */
    @Query("select l.transfer.id, count(l) from StockTransferLine l where l.transfer.id in :ids group by l.transfer.id")
    List<Object[]> countLines(@Param("ids") Collection<UUID> ids);
}
