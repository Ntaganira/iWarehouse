package com.ntaganira.heritier.iWarehouse.repository;

import com.ntaganira.heritier.iWarehouse.entity.PriceList;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PriceListRepository extends JpaRepository<PriceList, UUID> {

    /** Default first, then active ones. */
    List<PriceList> findAllByOrderByDefaultListDescEnabledDescCodeAsc();

    List<PriceList> findByEnabledTrueOrderByDefaultListDescNameAsc();

    Optional<PriceList> findByDefaultListTrue();

    boolean existsByCode(String code);
}
