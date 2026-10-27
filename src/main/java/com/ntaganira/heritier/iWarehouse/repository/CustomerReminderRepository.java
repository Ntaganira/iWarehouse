package com.ntaganira.heritier.iWarehouse.repository;

import com.ntaganira.heritier.iWarehouse.entity.CustomerReminder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.repository
 * - File      : CustomerReminderRepository.java
 * - Date      : 2026. 10. 15.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : Payment reminders issued to customers (BP 5.1). Append-only: save and finders only.
 * </pre>
 */
public interface CustomerReminderRepository extends Repository<CustomerReminder, UUID> {

    CustomerReminder save(CustomerReminder reminder);

    @EntityGraph(attributePaths = {"customer"})
    Optional<CustomerReminder> findById(UUID id);

    @EntityGraph(attributePaths = {"customer"})
    Page<CustomerReminder> findAllBy(Pageable pageable);

    @EntityGraph(attributePaths = {"customer"})
    Page<CustomerReminder> findByCustomer_Id(UUID customerId, Pageable pageable);

    /** The reminders of some customers, newest first (the last one each was sent). */
    @Query("select r from CustomerReminder r where r.customer.id in :ids order by r.createdAt desc")
    List<CustomerReminder> findOfCustomers(@Param("ids") Collection<UUID> ids);
}
