package com.ntaganira.heritier.iWarehouse.repository;

import com.ntaganira.heritier.iWarehouse.entity.ServicePrice;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface ServicePriceRepository extends JpaRepository<ServicePrice, UUID> {

    /** With the service, which the price page and the history show (open-in-view is off). */
    @EntityGraph(attributePaths = "service")
    List<ServicePrice> findByPriceList_Id(UUID priceListId);

    boolean existsByService_IdAndPriceIsNotNull(UUID serviceId);

    long countByPriceList_IdAndPriceIsNotNull(UUID priceListId);

    @Query("select s.id from ServicePrice s where s.priceList.id = :priceListId")
    List<UUID> findIdsByPriceListId(@Param("priceListId") UUID priceListId);
}
