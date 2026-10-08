package com.ntaganira.heritier.iWarehouse.repository;

import com.ntaganira.heritier.iWarehouse.entity.TaxCategory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TaxCategoryRepository extends JpaRepository<TaxCategory, UUID> {

    boolean existsByCodeIgnoreCase(String code);

    List<TaxCategory> findAllByOrderByEnabledDescCodeAsc();

    /** Active categories for product forms (TAX-01). */
    List<TaxCategory> findByEnabledTrueOrderByCodeAsc();

    Optional<TaxCategory> findByDefaultCategoryTrue();
}
