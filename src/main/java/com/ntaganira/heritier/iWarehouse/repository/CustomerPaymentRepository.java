package com.ntaganira.heritier.iWarehouse.repository;

import com.ntaganira.heritier.iWarehouse.entity.CustomerPayment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.repository
 * - File      : CustomerPaymentRepository.java
 * - Date      : 2026. 10. 09.
 * - User      : Hntaganira
 * - Desc      : Payments on customer accounts (ACC-09), with their customer; the cash a till took on accounts.
 * </pre>
 */
public interface CustomerPaymentRepository extends JpaRepository<CustomerPayment, UUID>, JpaSpecificationExecutor<CustomerPayment> {

    @EntityGraph(attributePaths = {"customer"})
    Page<CustomerPayment> findAll(Specification<CustomerPayment> spec, Pageable pageable);

    @EntityGraph(attributePaths = {"customer"})
    Optional<CustomerPayment> findDetailedById(UUID id);

    /** The cash payments taken in a till session, in the order they were taken. */
    @EntityGraph(attributePaths = {"customer"})
    List<CustomerPayment> findByTillSessionIdOrderByPostedAtAsc(UUID tillSessionId);

    /** Cash a till session took on customer accounts: it is in the drawer. */
    @Query("select coalesce(sum(p.amount), 0) from CustomerPayment p where p.tillSessionId = :sessionId")
    BigDecimal cashOfSession(@Param("sessionId") UUID sessionId);
}
