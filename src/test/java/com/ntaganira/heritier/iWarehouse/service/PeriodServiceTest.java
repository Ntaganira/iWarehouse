package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.entity.AccountingPeriod;
import com.ntaganira.heritier.iWarehouse.enums.ManualJournalStatus;
import com.ntaganira.heritier.iWarehouse.enums.PeriodStatus;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.repository.AccountingPeriodRepository;
import com.ntaganira.heritier.iWarehouse.repository.JournalEntryRepository;
import com.ntaganira.heritier.iWarehouse.repository.ManualJournalRepository;
import com.ntaganira.heritier.iWarehouse.security.AppUserPrincipal;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Monthly period close (ACC-10): months close in order once ended, not while a manual journal waits or a revaluation is due;
 * only the latest closed month reopens; the order of the close and what a closed day is.
 */
class PeriodServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-03T08:00:00Z"), ZoneId.of("Africa/Kigali"));

    private final Map<LocalDate, AccountingPeriod> periods = new TreeMap<>();
    private long waiting;
    private FxRevaluationService.MonthState revaluation = FxRevaluationService.MonthState.NOT_NEEDED;
    private Query lock;
    private PeriodService service;

    @BeforeEach
    void setUp() {
        AppUserPrincipal principal = new AppUserPrincipal(7L, "accountant", "accountant", "x", true, true, List.of());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
        AccountingPeriodRepository repo = mock(AccountingPeriodRepository.class);
        when(repo.closedThrough()).thenAnswer(a -> periods.values().stream().filter(AccountingPeriod::isClosed)
                .map(AccountingPeriod::getPeriodEnd).max(Comparator.naturalOrder()).orElse(null));
        when(repo.findByPeriodEnd(any())).thenAnswer(a -> Optional.ofNullable(periods.get(a.<LocalDate>getArgument(0))));
        when(repo.save(any())).thenAnswer(a -> {
            AccountingPeriod p = a.getArgument(0);
            if (p.getId() == null) {
                p.setId(UUID.randomUUID());
            }
            periods.put(p.getPeriodEnd(), p);
            return p;
        });
        when(repo.findById(any())).thenAnswer(a -> periods.values().stream().filter(p -> p.getId().equals(a.getArgument(0))).findFirst());
        JournalEntryRepository entryRepo = mock(JournalEntryRepository.class);
        when(entryRepo.firstEntryDate()).thenReturn(LocalDate.of(2026, 9, 14));
        ManualJournalRepository manualJournalRepo = mock(ManualJournalRepository.class);
        when(manualJournalRepo.countByStatusAndEntryDateLessThanEqual(eq(ManualJournalStatus.PENDING_APPROVAL), any())).thenAnswer(a -> waiting);
        FxRevaluationService fx = mock(FxRevaluationService.class);
        when(fx.state(any())).thenAnswer(a -> revaluation);
        when(fx.ofMonth(any())).thenReturn(Optional.empty());
        EntityManager em = mock(EntityManager.class);
        lock = mock(Query.class);
        when(em.createNativeQuery(anyString())).thenReturn(lock);
        service = new PeriodService(repo, entryRepo, manualJournalRepo, fx, em, CLOCK);
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void monthsCloseInOrderOnceEndedAndTheirBooksComplete() {
        assertThat(service.next()).contains(YearMonth.of(2026, 9));                    // the ledger's first month
        assertThatThrownBy(() -> service.close(YearMonth.of(2026, 10)))
                .isInstanceOf(BusinessException.class).extracting("messageKey").isEqualTo("period.notNext");

        waiting = 2;
        assertThatThrownBy(() -> service.close(YearMonth.of(2026, 9)))
                .isInstanceOf(BusinessException.class).extracting("messageKey").isEqualTo("period.journalsWaiting");
        waiting = 0;
        revaluation = FxRevaluationService.MonthState.DUE;
        assertThat(service.checklist(YearMonth.of(2026, 9)).isReady()).isFalse();
        assertThatThrownBy(() -> service.close(YearMonth.of(2026, 9)))
                .isInstanceOf(BusinessException.class).extracting("messageKey").isEqualTo("period.revaluationDue");
        revaluation = FxRevaluationService.MonthState.DONE;

        AccountingPeriod september = service.close(YearMonth.of(2026, 9));
        assertThat(september.getStatus()).isEqualTo(PeriodStatus.CLOSED);
        assertThat(september.getPeriodEnd()).isEqualTo(LocalDate.of(2026, 9, 30));
        assertThat(september.getClosedBy()).isEqualTo("accountant");
        assertThat(service.closedThrough()).isEqualTo(LocalDate.of(2026, 9, 30));
        verify(lock, atLeastOnce()).executeUpdate();                                   // the journal table is locked while closing

        assertThat(service.next()).contains(YearMonth.of(2026, 10));
        service.close(YearMonth.of(2026, 10));
        assertThat(service.next()).isEmpty();                                          // November has not ended
        assertThat(service.following()).contains(YearMonth.of(2026, 11));
    }

    @Test
    void onlyTheLatestClosedMonthReopensAndClosesAgain() {
        AccountingPeriod september = service.close(YearMonth.of(2026, 9));
        AccountingPeriod october = service.close(YearMonth.of(2026, 10));
        assertThat(service.canReopen(september)).isFalse();
        assertThatThrownBy(() -> service.reopen(september.getId(), "Late invoice"))
                .isInstanceOf(BusinessException.class).extracting("messageKey").isEqualTo("period.notLatest");

        service.reopen(october.getId(), "  Late supplier invoice  ");
        assertThat(october.getStatus()).isEqualTo(PeriodStatus.REOPENED);
        assertThat(october.getReopenReason()).isEqualTo("Late supplier invoice");
        assertThat(october.getReopenedBy()).isEqualTo("accountant");
        assertThat(service.closedThrough()).isEqualTo(LocalDate.of(2026, 9, 30));
        assertThat(service.next()).contains(YearMonth.of(2026, 10));

        AccountingPeriod again = service.close(YearMonth.of(2026, 10));
        assertThat(again).isSameAs(october);                                           // the same month closed again
        assertThat(again.getStatus()).isEqualTo(PeriodStatus.CLOSED);
        assertThat(again.getReopenReason()).isEqualTo("Late supplier invoice");        // what happened stays
    }

    @Test
    void theOrderOfTheCloseAndWhatAClosedDayIs() {
        LocalDate first = LocalDate.of(2026, 9, 14);
        assertThat(Periods.nextToClose(first, null, LocalDate.of(2026, 10, 1))).contains(YearMonth.of(2026, 9));
        assertThat(Periods.nextToClose(first, null, LocalDate.of(2026, 9, 30))).isEmpty();   // not ended yet
        assertThat(Periods.nextToClose(first, LocalDate.of(2026, 9, 30), LocalDate.of(2026, 11, 1))).contains(YearMonth.of(2026, 10));
        assertThat(Periods.nextToClose(null, null, LocalDate.of(2026, 11, 1))).isEmpty();    // no journal yet

        assertThat(Periods.isClosed(LocalDate.of(2026, 9, 30), LocalDate.of(2026, 9, 30))).isTrue();
        assertThat(Periods.isClosed(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 9, 30))).isTrue();
        assertThat(Periods.isClosed(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 9, 30))).isFalse();
        assertThat(Periods.isClosed(LocalDate.of(2026, 10, 1), null)).isFalse();
    }
}
