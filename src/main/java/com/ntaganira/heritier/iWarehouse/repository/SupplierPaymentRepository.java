package com.ntaganira.heritier.iWarehouse.repository;

import com.ntaganira.heritier.iWarehouse.entity.SupplierPayment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.repository
 * - File      : SupplierPaymentRepository.java
 * - Date      : 2026. 10. 09.
 * - User      : Hntaganira
 * - Desc      : Payments to suppliers (ACC-08, ACC-09), with their supplier.
 * </pre>
 */
public interface SupplierPaymentRepository extends JpaRepository<SupplierPayment, UUID>, JpaSpecificationExecutor<SupplierPayment> {

    @EntityGraph(attributePaths = {"supplier"})
    Page<SupplierPayment> findAll(Specification<SupplierPayment> spec, Pageable pageable);

    @EntityGraph(attributePaths = {"supplier"})
    Optional<SupplierPayment> findDetailedById(UUID id);
}
