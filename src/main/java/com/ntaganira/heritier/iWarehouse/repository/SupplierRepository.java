package com.ntaganira.heritier.iWarehouse.repository;

import com.ntaganira.heritier.iWarehouse.entity.Supplier;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SupplierRepository extends JpaRepository<Supplier, UUID>, JpaSpecificationExecutor<Supplier> {

    /** An invoice or a payment of the supplier's takes this lock first, so two never match or settle the same thing. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Supplier s where s.id = :id")
    Optional<Supplier> lockById(@Param("id") UUID id);

    boolean existsByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCaseAndIdNot(String name, UUID id);

    /** Active suppliers invoicing in a currency: that currency must stay active (ACC-02). */
    long countByCurrencyCodeAndEnabledTrue(String currencyCode);

    /** Countries in use, for the list filter. */
    @Query("select distinct s.countryCode from Supplier s")
    List<String> findCountryCodes();

    /** Currencies in use, for the list filter. */
    @Query("select distinct s.currencyCode from Supplier s order by s.currencyCode")
    List<String> findCurrencyCodes();
}
