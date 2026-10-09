package com.ntaganira.heritier.iWarehouse.repository;

import com.ntaganira.heritier.iWarehouse.entity.Customer;
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

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CustomerRepository extends JpaRepository<Customer, UUID>, JpaSpecificationExecutor<Customer> {

    /** A payment on the customer's account takes this lock first, so two payments never settle more than they owe. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Customer c where c.id = :id")
    Optional<Customer> lockById(@Param("id") UUID id);

    /** The list shows each customer's price list, so it is fetched with the page (open-in-view is off). */
    @Override
    @EntityGraph(attributePaths = "priceList")
    Page<Customer> findAll(Specification<Customer> spec, Pageable pageable);

    @EntityGraph(attributePaths = "priceList")
    Optional<Customer> findWithPriceListById(UUID id);

    Optional<Customer> findByTin(String tin);

    Optional<Customer> findByDefaultCustomerTrue();

    long countByPriceList_IdAndEnabledTrue(UUID priceListId);

    List<Customer> findByPriceList_IdOrderByNameAsc(UUID priceListId);

    /** Customers a document can be for, by name. */
    List<Customer> findByEnabledTrueOrderByNameAsc();
}
