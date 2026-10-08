package com.ntaganira.heritier.iWarehouse.repository;

import com.ntaganira.heritier.iWarehouse.entity.PriceListItem;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PriceListItemRepository extends JpaRepository<PriceListItem, UUID> {

    /** With the product, which the price page and the history show (open-in-view is off). */
    @EntityGraph(attributePaths = "product")
    List<PriceListItem> findByPriceList_Id(UUID priceListId);

    Optional<PriceListItem> findByPriceList_IdAndProduct_Id(UUID priceListId, UUID productId);

    long countByPriceList_IdAndPricePerM2IsNotNull(UUID priceListId);

    @Query("select i.id from PriceListItem i where i.priceList.id = :priceListId")
    List<UUID> findIdsByPriceListId(@Param("priceListId") UUID priceListId);
}
