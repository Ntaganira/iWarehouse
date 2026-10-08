package com.ntaganira.heritier.iWarehouse.repository;

import com.ntaganira.heritier.iWarehouse.entity.Product;
import com.ntaganira.heritier.iWarehouse.enums.GlassType;
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

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProductRepository extends JpaRepository<Product, UUID>, JpaSpecificationExecutor<Product> {

    /** The list shows each product's VAT category, so it is fetched with the page (open-in-view is off). */
    @Override
    @EntityGraph(attributePaths = "taxCategory")
    Page<Product> findAll(Specification<Product> spec, Pageable pageable);

    @EntityGraph(attributePaths = "taxCategory")
    Optional<Product> findWithTaxCategoryById(UUID id);

    boolean existsByCode(String code);

    boolean existsByCodeAndIdNot(String code, UUID id);

    /** Same type, colour/finish (ignoring case; '' for none) and thickness: uk_products_identity. */
    @Query("select count(p) > 0 from Product p where p.glassType = :type"
            + " and lower(coalesce(p.variant, '')) = lower(:variant) and p.thicknessMm = :thickness")
    boolean existsIdentity(@Param("type") GlassType type, @Param("variant") String variant,
                           @Param("thickness") BigDecimal thickness);

    /** Thicknesses in use, for the list filter. */
    @Query("select distinct p.thicknessMm from Product p order by p.thicknessMm")
    List<BigDecimal> findThicknesses();

    /**
     * Locks products before their stock is read and their moving average cost changed (PRC-05), so two
     * receipts of the same product are posted one after the other. Locked in id order (no deadlock).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Product p where p.id in :ids order by p.id")
    List<Product> lockAllById(@Param("ids") Collection<UUID> ids);
}
