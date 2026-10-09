package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.entity.Account;
import com.ntaganira.heritier.iWarehouse.entity.FxRevaluation;
import com.ntaganira.heritier.iWarehouse.entity.FxRevaluationLine;
import com.ntaganira.heritier.iWarehouse.entity.Supplier;
import com.ntaganira.heritier.iWarehouse.enums.AccountKey;
import com.ntaganira.heritier.iWarehouse.enums.DocumentType;
import com.ntaganira.heritier.iWarehouse.enums.RateSource;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.repository.*;
import com.ntaganira.heritier.iWarehouse.security.AppUserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Month-end revaluation (ACC-08): the open foreign balances of Accounts Payable, GRNI and Accrued Import Charges at the
 * month's last day, each at that day's rate; refused while a rate is missing, before the month has ended and once revalued.
 */
class FxRevaluationServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-09T08:00:00Z"), ZoneId.of("Africa/Kigali"));
    private static final LocalDate END = LocalDate.of(2026, 9, 30);

    private final Map<AccountKey, Account> accounts = new EnumMap<>(AccountKey.class);
    private final List<Object[]> balances = new ArrayList<>();
    private final List<FxRevaluationLine> saved = new ArrayList<>();
    private final List<LocalDate> revalued = new ArrayList<>();
    private Supplier shandong;
    private ExchangeRateService rates;
    private PostingService postings;
    private FxRevaluationService service;

    @BeforeEach
    void setUp() {
        AppUserPrincipal principal = new AppUserPrincipal(7L, "accountant", "accountant", "x", true, true, List.of());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
        account(AccountKey.PAYABLE, "2010");
        account(AccountKey.GRNI, "2020");
        account(AccountKey.IMPORT_ACCRUAL, "2030");
        account(AccountKey.BANK, "1030");                                            // never revalued: an RWF account
        shandong = new Supplier();
        shandong.setId(UUID.randomUUID());
        shandong.setName("Shandong Float Glass Co.");

        // At 30/09: 3,500 USD owed to Shandong booked at 4,625,000; 1,000 USD received not invoiced at 1,449,123.46;
        // a 200 EUR import bill without a supplier at 300,000; a USD invoice paid in full
        balances.add(row(AccountKey.IMPORT_ACCRUAL, null, "EUR", "200", "300000"));
        balances.add(row(AccountKey.PAYABLE, shandong, "USD", "3500", "4625000"));
        balances.add(row(AccountKey.GRNI, shandong, "USD", "1000", "1449123.46"));
        balances.add(row(AccountKey.PAYABLE, shandong, "CNY", "0", "0"));

        FxRevaluationRepository repo = mock(FxRevaluationRepository.class);
        when(repo.save(any())).thenAnswer(a -> {
            FxRevaluation r = a.getArgument(0);
            r.setId(UUID.randomUUID());
            revalued.add(r.getPeriodEnd());
            return r;
        });
        when(repo.existsByPeriodEnd(any())).thenAnswer(a -> revalued.contains(a.getArgument(0)));
        when(repo.findPeriodEnds()).thenAnswer(a -> revalued);
        FxRevaluationLineRepository lineRepo = mock(FxRevaluationLineRepository.class);
        when(lineRepo.save(any())).thenAnswer(a -> {
            saved.add(a.getArgument(0));
            return a.getArgument(0);
        });
        JournalLineRepository journalLineRepo = mock(JournalLineRepository.class);
        when(journalLineRepo.foreignBalances(any(), eq(END))).thenAnswer(a -> {
            Collection<UUID> ids = a.getArgument(0);
            assertThat(ids).containsExactlyInAnyOrder(accounts.get(AccountKey.PAYABLE).getId(), accounts.get(AccountKey.GRNI).getId(),
                    accounts.get(AccountKey.IMPORT_ACCRUAL).getId());
            return balances;
        });
        JournalEntryRepository entryRepo = mock(JournalEntryRepository.class);
        when(entryRepo.firstEntryDate()).thenReturn(LocalDate.of(2026, 8, 14));
        AccountRepository accountRepo = mock(AccountRepository.class);
        when(accountRepo.findBySystemKeyIsNotNull()).thenReturn(List.copyOf(accounts.values()));
        SupplierRepository supplierRepo = mock(SupplierRepository.class);
        when(supplierRepo.findAllById(any())).thenReturn(List.of(shandong));
        rates = mock(ExchangeRateService.class);
        when(rates.rateFor(eq("USD"), eq(END))).thenReturn(new ExchangeRateService.AppliedRate("USD", new BigDecimal("1320"),
                END, RateSource.BNR));
        when(rates.rateFor(eq("EUR"), eq(END))).thenThrow(BusinessException.of("rate.missing", "EUR", "BNR", END));
        postings = mock(PostingService.class);
        DocumentNumberService numbers = mock(DocumentNumberService.class);
        when(numbers.next(DocumentType.FX_REVALUATION)).thenReturn("FXR-WH-2026-000001");
        service = new FxRevaluationService(repo, lineRepo, journalLineRepo, entryRepo, accountRepo, supplierRepo, rates, postings,
                numbers, CLOCK);
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void theOpenBalancesAreRevaluedAtTheMonthsLastRateAndAMissingRateStopsThePosting() {
        FxRevaluationService.Preview preview = service.preview(YearMonth.of(2026, 9));

        assertThat(preview.periodEnd()).isEqualTo(END);
        assertThat(preview.rows()).extracting(r -> r.account().getCode() + " " + r.currencyCode())
                .containsExactly("2010 USD", "2020 USD", "2030 EUR");                  // the settled CNY left out
        FxRevaluationService.Row payable = preview.rows().get(0);
        assertThat(payable.supplier()).isSameAs(shandong);
        assertThat(payable.revaluedOwed()).isEqualByComparingTo("4620000");             // 3,500 x 1,320
        assertThat(payable.gainLoss()).isEqualByComparingTo("5000");
        assertThat(preview.rows().get(1).gainLoss()).isEqualByComparingTo("129123.46");  // 1,449,123.46 - 1,320,000
        assertThat(preview.rows().get(2).rate()).isNull();                              // no EUR rate
        assertThat(preview.missingRates()).extracting(BusinessException::getMessageKey).containsExactly("rate.missing");
        assertThat(preview.isReady()).isFalse();

        assertThatThrownBy(() -> service.post(YearMonth.of(2026, 9)))
                .isInstanceOf(BusinessException.class).extracting("messageKey").isEqualTo("rate.missing");
        verifyNoInteractions(postings);
    }

    @Test
    void postingKeepsEachLineWithItsRateAndPostsTheJournalAndItsReversal() {
        doReturn(new ExchangeRateService.AppliedRate("EUR", new BigDecimal("1600"), LocalDate.of(2026, 9, 29), RateSource.BNR))
                .when(rates).rateFor(eq("EUR"), eq(END));

        FxRevaluation revaluation = service.post(YearMonth.of(2026, 9));

        assertThat(revaluation.getNumber()).isEqualTo("FXR-WH-2026-000001");
        assertThat(revaluation.getPeriodEnd()).isEqualTo(END);
        assertThat(revaluation.getPostedBy()).isEqualTo("accountant");
        assertThat(revaluation.getGainLoss()).isEqualByComparingTo("114123.46");        // 5,000 + 129,123.46 - 20,000
        assertThat(saved).extracting(FxRevaluationLine::getLineNo).containsExactly(1, 2, 3);
        FxRevaluationLine accrual = saved.get(2);
        assertThat(accrual.getSupplier()).isNull();
        assertThat(accrual.getFxOwed()).isEqualByComparingTo("200");
        assertThat(accrual.getRate()).isEqualByComparingTo("1600");
        assertThat(accrual.getRateDate()).isEqualTo(LocalDate.of(2026, 9, 29));
        assertThat(accrual.getRateSource()).isEqualTo(RateSource.BNR);
        assertThat(accrual.getRevaluedOwed()).isEqualByComparingTo("320000");
        assertThat(accrual.getGainLoss()).isEqualByComparingTo("-20000");
        assertThat(saved).allSatisfy(l -> assertThat(l.getRevaluationId()).isEqualTo(revaluation.getId()));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<FxRevaluationLine>> lines = ArgumentCaptor.forClass(List.class);
        verify(postings).fxRevaluation(eq(revaluation), lines.capture());
        assertThat(lines.getValue()).hasSize(3);

        // Once only, and the month is no longer offered
        assertThat(service.months()).containsExactly(YearMonth.of(2026, 8));
        assertThatThrownBy(() -> service.post(YearMonth.of(2026, 9)))
                .isInstanceOf(BusinessException.class).extracting("messageKey").isEqualTo("fxRevaluation.done");
        assertThatThrownBy(() -> service.preview(YearMonth.of(2026, 9)))
                .isInstanceOf(BusinessException.class).extracting("messageKey").isEqualTo("fxRevaluation.done");
    }

    @Test
    void aMonthNotEndedOrWithNothingToMoveIsRefused() {
        assertThatThrownBy(() -> service.preview(YearMonth.of(2026, 10)))
                .isInstanceOf(BusinessException.class).extracting("messageKey").isEqualTo("fxRevaluation.notEnded");
        assertThatThrownBy(() -> service.post(null))
                .isInstanceOf(BusinessException.class).extracting("messageKey").isEqualTo("fxRevaluation.notEnded");

        balances.clear();
        balances.add(row(AccountKey.PAYABLE, shandong, "USD", "1000", "1320000"));      // booked at the month's rate
        assertThat(service.preview(YearMonth.of(2026, 9)).isReady()).isFalse();
        assertThatThrownBy(() -> service.post(YearMonth.of(2026, 9)))
                .isInstanceOf(BusinessException.class).extracting("messageKey").isEqualTo("fxRevaluation.nothing");
        verify(postings, never()).fxRevaluation(any(), anyList());
    }

    private void account(AccountKey key, String code) {
        Account a = new Account();
        a.setId(UUID.randomUUID());
        a.setCode(code);
        a.setName(key.name());
        a.setSystemKey(key);
        accounts.put(key, a);
    }

    private Object[] row(AccountKey key, Supplier supplier, String currency, String owed, String booked) {
        return new Object[]{accounts.get(key).getId(), supplier == null ? null : supplier.getId(), currency, new BigDecimal(owed),
                new BigDecimal(booked)};
    }
}
