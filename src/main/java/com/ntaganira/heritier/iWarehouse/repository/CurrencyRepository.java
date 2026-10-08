package com.ntaganira.heritier.iWarehouse.repository;

import com.ntaganira.heritier.iWarehouse.entity.Currency;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CurrencyRepository extends JpaRepository<Currency, UUID> {

    Optional<Currency> findByCode(String code);

    boolean existsByCode(String code);

    /** Base currency first, then active ones, then by code. */
    List<Currency> findAllByOrderByBaseCurrencyDescEnabledDescCodeAsc();

    /** Currencies that need exchange rates: active and not the base. */
    List<Currency> findByEnabledTrueAndBaseCurrencyFalseOrderByCodeAsc();

    Optional<Currency> findByBaseCurrencyTrue();
}
