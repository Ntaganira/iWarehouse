package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.dto.CurrencyDto;
import com.ntaganira.heritier.iWarehouse.dto.ExchangeRateDto;
import com.ntaganira.heritier.iWarehouse.entity.Currency;
import com.ntaganira.heritier.iWarehouse.entity.ExchangeRate;
import com.ntaganira.heritier.iWarehouse.enums.RateSource;
import com.ntaganira.heritier.iWarehouse.enums.SettingKey;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.repository.CurrencyRepository;
import com.ntaganira.heritier.iWarehouse.repository.ExchangeRateRepository;
import com.ntaganira.heritier.iWarehouse.repository.SupplierRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

import static com.ntaganira.heritier.iWarehouse.enums.RateSource.BNR;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Rate lookup for documents, recording, correcting and importing rates (ACC-02), on 7 October 2026. */
class ExchangeRateServiceTest {

    private static final ZoneId KIGALI = ZoneId.of("Africa/Kigali");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 7);

    private ExchangeRateRepository repo;
    private CurrencyRepository currencyRepo;
    private SettingService settings;
    private ExchangeRateService service;

    private final Currency rwf = currency("RWF", true, true);
    private final Currency usd = currency("USD", false, true);
    private final Currency eur = currency("EUR", false, true);
    private final Currency gbp = currency("GBP", false, false);

    @BeforeEach
    void setUp() {
        repo = mock(ExchangeRateRepository.class);
        currencyRepo = mock(CurrencyRepository.class);
        settings = mock(SettingService.class);
        when(settings.get(SettingKey.DEFAULT_RATE_SOURCE)).thenReturn("BNR");
        when(settings.getInt(SettingKey.MAX_RATE_AGE_DAYS)).thenReturn(7);
        for (Currency c : List.of(rwf, usd, eur, gbp)) {
            when(currencyRepo.findByCode(c.getCode())).thenReturn(Optional.of(c));
        }
        when(currencyRepo.findByEnabledTrueAndBaseCurrencyFalseOrderByCodeAsc()).thenReturn(List.of(eur, usd));
        when(repo.findFirstByCurrencyCodeAndSourceAndRateDateLessThanEqualOrderByRateDateDesc(any(), any(), any()))
                .thenReturn(Optional.empty());
        when(repo.findFirstByCurrencyCodeAndSourceAndRateDateLessThanOrderByRateDateDesc(any(), any(), any()))
                .thenReturn(Optional.empty());
        when(repo.findByCurrencyCodeAndRateDateAndSource(any(), any(), any())).thenReturn(Optional.empty());
        when(repo.save(any(ExchangeRate.class))).thenAnswer(i -> i.getArgument(0));
        Clock clock = Clock.fixed(TODAY.atTime(9, 0).atZone(KIGALI).toInstant(), KIGALI);
        service = new ExchangeRateService(repo, currencyRepo, settings, clock);
    }

    // ---------------------------------------------------------------- rateFor

    @Test
    void rwfIsAlwaysOne() {
        ExchangeRateService.AppliedRate applied = service.rateFor("RWF", TODAY);
        assertThat(applied.rate()).isEqualByComparingTo("1");
        assertThat(applied.toBase(new BigDecimal("2500"))).isEqualByComparingTo("2500");
    }

    @Test
    void documentUsesTheLatestRateOnOrBeforeItsDate() {
        ExchangeRate oct5 = rate("USD", LocalDate.of(2026, 10, 5), "1448.10");
        when(repo.findFirstByCurrencyCodeAndSourceAndRateDateLessThanEqualOrderByRateDateDesc("USD", BNR, TODAY))
                .thenReturn(Optional.of(oct5));

        ExchangeRateService.AppliedRate applied = service.rateFor("USD", TODAY);

        assertThat(applied.rate()).isEqualByComparingTo("1448.10");
        assertThat(applied.rateDate()).isEqualTo(LocalDate.of(2026, 10, 5));
        assertThat(applied.source()).isEqualTo(BNR);
        assertThat(applied.toBase(new BigDecimal("100"))).isEqualByComparingTo("144810");
    }

    @Test
    void missingRateBlocksTheDocument() {
        assertThatThrownBy(() -> service.rateFor("EUR", TODAY))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getMessageKey()).isEqualTo("rate.missing"));
    }

    @Test
    void rateOlderThanTheLimitIsRefused() {
        when(repo.findFirstByCurrencyCodeAndSourceAndRateDateLessThanEqualOrderByRateDateDesc("USD", BNR, TODAY))
                .thenReturn(Optional.of(rate("USD", TODAY.minusDays(8), "1440")));
        assertThatThrownBy(() -> service.rateFor("USD", TODAY))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getMessageKey()).isEqualTo("rate.stale");
                    assertThat(e.getArgs()).contains(8L, 7);
                });

        when(repo.findFirstByCurrencyCodeAndSourceAndRateDateLessThanEqualOrderByRateDateDesc("USD", BNR, TODAY))
                .thenReturn(Optional.of(rate("USD", TODAY.minusDays(7), "1440")));
        assertThat(service.rateFor("USD", TODAY).rate()).isEqualByComparingTo("1440"); // exactly the limit is fine
    }

    @Test
    void inactiveCurrencyIsRefused() {
        assertThatThrownBy(() -> service.rateFor("GBP", TODAY))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getMessageKey()).isEqualTo("rate.currency.unknown"));
    }

    @Test
    void unreadableDefaultSourceFallsBackToBnr() {
        when(settings.get(SettingKey.DEFAULT_RATE_SOURCE)).thenReturn("NOPE");
        assertThat(service.defaultSource()).isEqualTo(BNR);
    }

    // ---------------------------------------------------------------- create / correct

    @Test
    void futureDateDuplicateAndBaseCurrencyAreRefused() {
        assertField(() -> service.create(dto("USD", TODAY.plusDays(1), "1450")), "rateDate", "rate.date.future");
        assertField(() -> service.create(dto("RWF", TODAY, "1")), "currencyCode", "rate.currency.invalid");

        when(repo.findByCurrencyCodeAndRateDateAndSource("USD", TODAY, BNR))
                .thenReturn(Optional.of(rate("USD", TODAY, "1450")));
        assertField(() -> service.create(dto("USD", TODAY, "1451")), "rateDate", "rate.exists");
    }

    @Test
    void largeChangeNeedsConfirming() {
        when(repo.findFirstByCurrencyCodeAndSourceAndRateDateLessThanOrderByRateDateDesc("USD", BNR, TODAY))
                .thenReturn(Optional.of(rate("USD", TODAY.minusDays(1), "1450.20")));

        assertField(() -> service.create(dto("USD", TODAY, "14502")), "rate", "rate.largeChange");

        ExchangeRateDto confirmed = dto("USD", TODAY, "14502");
        confirmed.setConfirmed(true);
        assertThat(service.create(confirmed).getRate()).isEqualByComparingTo("14502");
        assertThat(service.create(dto("USD", TODAY, "1452.10")).getRate()).isEqualByComparingTo("1452.10");
    }

    @Test
    void correctionNeedsAReasonAndComparesWithThePreviousDay() {
        ExchangeRate typo = rate("USD", TODAY, "14502");
        when(repo.findById(typo.getId())).thenReturn(Optional.of(typo));
        when(repo.findFirstByCurrencyCodeAndSourceAndRateDateLessThanOrderByRateDateDesc("USD", BNR, TODAY))
                .thenReturn(Optional.of(rate("USD", TODAY.minusDays(1), "1448.10")));

        ExchangeRateDto fix = dto("USD", TODAY, "1450.20");
        assertField(() -> service.correct(typo.getId(), fix), "reason", "rate.reason.required");

        fix.setReason("Decimal point slipped");
        ExchangeRateService.Correction correction = service.correct(typo.getId(), fix);

        // 1450.20 is 300% below the typo but only 0.15% from yesterday: no confirmation needed.
        assertThat(correction.changed()).isTrue();
        assertThat(correction.oldRate()).isEqualByComparingTo("14502");
        assertThat(typo.getRate()).isEqualByComparingTo("1450.20");
    }

    @Test
    void correctionWithoutChangeChangesNothing() {
        ExchangeRate usdRate = rate("USD", TODAY, "1450.200000");
        when(repo.findById(usdRate.getId())).thenReturn(Optional.of(usdRate));
        ExchangeRateDto same = dto("USD", TODAY, "1450.2");
        same.setReason("checking");

        assertThat(service.correct(usdRate.getId(), same).changed()).isFalse();
    }

    // ---------------------------------------------------------------- import

    @Test
    void importSavesEveryLineWhenAllAreFine() {
        when(repo.findByCurrencyCodeAndRateDateAndSource("USD", LocalDate.of(2026, 10, 5), BNR))
                .thenReturn(Optional.of(rate("USD", LocalDate.of(2026, 10, 5), "1446.00")));

        ExchangeRateService.ImportResult result = service.importCsv(
                "date,currency,rate\n2026-10-05,USD,1446\n2026-10-06,USD,1448.10\n2026-10-07,EUR,1689.40\n", BNR, false, "bnr.csv");

        assertThat(result.ok()).isTrue();
        assertThat(result.added()).isEqualTo(2);
        assertThat(result.unchanged()).isEqualTo(1);
        verify(repo).saveAll(argThat((List<ExchangeRate> rows) -> rows.size() == 2
                && rows.stream().allMatch(r -> "Imported from bnr.csv".equals(r.getNote()) && r.getSource() == BNR)));
    }

    @Test
    void importIsAllOrNothing() {
        when(repo.findByCurrencyCodeAndRateDateAndSource("USD", LocalDate.of(2026, 10, 5), BNR))
                .thenReturn(Optional.of(rate("USD", LocalDate.of(2026, 10, 5), "1446.00")));
        String file = String.join("\n",
                "2026-10-05,USD,1447",     // 1: differs from the recorded rate
                "2026-10-06,USD,1448.10",  // 2: fine
                "2026-10-06,USD,1448.20",  // 3: twice in the file
                "2026-10-07,GBP,1900",     // 4: not active
                "2026-10-08,EUR,1690",     // 5: tomorrow
                "2026-10-06,EUR,1689",     // 6: fine
                "2026-10-07,EUR,16894");   // 7: 900% jump from line 6
        ExchangeRateService.ImportResult result = service.importCsv(file, BNR, false, "rates.csv");

        assertThat(result.ok()).isFalse();
        assertThat(result.problems()).extracting(RateCsv.Problem::line).containsExactly(1, 3, 4, 5, 7);
        assertThat(result.problems()).extracting(RateCsv.Problem::messageKey).containsExactly(
                "rate.import.conflict", "rate.import.duplicate", "rate.import.notActive", "rate.date.future",
                "rate.import.largeChange");
        verify(repo, never()).saveAll(anyList());
    }

    @Test
    void confirmedImportAcceptsLargeChanges() {
        ExchangeRateService.ImportResult result = service.importCsv(
                "2026-10-06,EUR,1689\n2026-10-07,EUR,16894\n", BNR, true, "rates.csv");
        assertThat(result.ok()).isTrue();
        assertThat(result.added()).isEqualTo(2);
    }

    // ---------------------------------------------------------------- CurrencyService

    @Test
    void baseCurrencyStaysActiveWithItsDecimals() {
        CurrencyService currencies = new CurrencyService(currencyRepo, mock(SupplierRepository.class));
        when(currencyRepo.findById(rwf.getId())).thenReturn(Optional.of(rwf));

        assertThatThrownBy(() -> currencies.setEnabled(rwf.getId(), false))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getMessageKey()).isEqualTo("currency.base.disable"));
        CurrencyDto twoDecimals = currencyDto("RWF", 2);
        assertField(() -> currencies.update(rwf.getId(), twoDecimals), "decimals", "currency.base.decimals");

        when(currencyRepo.existsByCode("USD")).thenReturn(true);
        assertField(() -> currencies.create(currencyDto("usd", 2)), "code", "currency.code.taken");
    }

    @Test
    void currencyStaysActiveWhileActiveSuppliersInvoiceInIt() {
        SupplierRepository suppliers = mock(SupplierRepository.class);
        CurrencyService currencies = new CurrencyService(currencyRepo, suppliers);
        when(currencyRepo.findById(usd.getId())).thenReturn(Optional.of(usd));
        when(suppliers.countByCurrencyCodeAndEnabledTrue("USD")).thenReturn(2L);

        assertThatThrownBy(() -> currencies.setEnabled(usd.getId(), false))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getMessageKey()).isEqualTo("currency.disable.suppliers");
                    assertThat(e.getArgs()).containsExactly("USD", 2L);
                });
        assertThat(usd.isEnabled()).isTrue();
    }

    // ---------------------------------------------------------------- helpers

    private static void assertField(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String field, String key) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class, e -> {
            assertThat(e.getField()).isEqualTo(field);
            assertThat(e.getMessageKey()).isEqualTo(key);
        });
    }

    private static Currency currency(String code, boolean base, boolean enabled) {
        Currency c = new Currency();
        c.setId(UUID.randomUUID());
        c.setCode(code);
        c.setName(code);
        c.setSymbol(code);
        c.setDecimals(base ? 0 : 2);
        c.setBaseCurrency(base);
        c.setEnabled(enabled);
        return c;
    }

    private static ExchangeRate rate(String code, LocalDate date, String value) {
        ExchangeRate r = new ExchangeRate();
        r.setId(UUID.randomUUID());
        r.setCurrencyCode(code);
        r.setRateDate(date);
        r.setSource(BNR);
        r.setRate(new BigDecimal(value));
        return r;
    }

    private static ExchangeRateDto dto(String code, LocalDate date, String value) {
        ExchangeRateDto dto = new ExchangeRateDto();
        dto.setCurrencyCode(code);
        dto.setRateDate(date);
        dto.setSource(RateSource.BNR);
        dto.setRate(new BigDecimal(value));
        return dto;
    }

    private static CurrencyDto currencyDto(String code, int decimals) {
        CurrencyDto dto = new CurrencyDto();
        dto.setCode(code);
        dto.setName("Name");
        dto.setSymbol("S");
        dto.setDecimals(decimals);
        return dto;
    }
}
