package com.ntaganira.heritier.iWarehouse.repository;

import com.ntaganira.heritier.iWarehouse.entity.SupplierInvoice;
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
 * - File      : SupplierInvoiceRepository.java
 * - Date      : 2026. 10. 09.
 * - User      : Hntaganira
 * - Desc      : Supplier invoices (ACC-09), with their supplier.
 * </pre>
 */
public interface SupplierInvoiceRepository extends JpaRepository<SupplierInvoice, UUID>, JpaSpecificationExecutor<SupplierInvoice> {

    @EntityGraph(attributePaths = {"supplier"})
    Page<SupplierInvoice> findAll(Specification<SupplierInvoice> spec, Pageable pageable);

    @EntityGraph(attributePaths = {"supplier"})
    Optional<SupplierInvoice> findDetailedById(UUID id);

    boolean existsBySupplier_IdAndSupplierRefIgnoreCase(UUID supplierId, String supplierRef);
}
