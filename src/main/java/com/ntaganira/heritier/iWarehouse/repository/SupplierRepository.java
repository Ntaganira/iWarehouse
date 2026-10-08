package com.ntaganira.heritier.iWarehouse.repository;

import com.ntaganira.heritier.iWarehouse.entity.Supplier;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface SupplierRepository extends JpaRepository<Supplier, UUID>, JpaSpecificationExecutor<Supplier> {

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
