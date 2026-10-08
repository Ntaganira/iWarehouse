package com.ntaganira.heritier.iWarehouse.repository;

import com.ntaganira.heritier.iWarehouse.entity.ExchangeRate;
import com.ntaganira.heritier.iWarehouse.enums.RateSource;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

public interface ExchangeRateRepository extends JpaRepository<ExchangeRate, UUID>, JpaSpecificationExecutor<ExchangeRate> {

    /** Latest rate on or before the date: the rate that applies on that date. */
    Optional<ExchangeRate> findFirstByCurrencyCodeAndSourceAndRateDateLessThanEqualOrderByRateDateDesc(
            String currencyCode, RateSource source, LocalDate date);

    /** Latest rate strictly before the date: the one a new rate is compared with. */
    Optional<ExchangeRate> findFirstByCurrencyCodeAndSourceAndRateDateLessThanOrderByRateDateDesc(
            String currencyCode, RateSource source, LocalDate date);

    Optional<ExchangeRate> findByCurrencyCodeAndRateDateAndSource(String currencyCode, LocalDate rateDate, RateSource source);
}
