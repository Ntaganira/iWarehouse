package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.entity.StockDayReview;
import com.ntaganira.heritier.iWarehouse.entity.StockReconciliation;
import com.ntaganira.heritier.iWarehouse.enums.*;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.repository.*;
import com.ntaganira.heritier.iWarehouse.security.AppUserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Reviews of the stock movements: a day is signed off once it has come, once (IMP 12); an ended month is signed off once,
 * keeping the vehicles', sites' and tickets' figures and the glass lost against the inventory account, with a note and
 * the Internal Controller told when that is above the Settings' tolerance (BP 7.4, Annex I step 5).
 */
class StockReviewServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-15T08:00:00Z"), ZoneId.of("Africa/Kigali"));
    private static final YearMonth SEPTEMBER = YearMonth.of(2026, 9);

    private StockMovementRepository movements;
    private StockDayReviewRepository days;
    private StockReconciliationRepository recons;
    private Notifier notifier;
    private StockReviewService service;

    @BeforeEach
    void setUp() {
        movements = mock(StockMovementRepository.class);
        days = mock(StockDayReviewRepository.class);
        when(days.save(any())).thenAnswer(a -> a.getArgument(0));
        recons = mock(StockReconciliationRepository.class);
        when(recons.save(any())).thenAnswer(a -> a.getArgument(0));
        TripRepository trips = mock(TripRepository.class);
        when(trips.lineOutcomesDepartedBetween(any(), any())).thenReturn(List.of(
                new Object[]{TripLineOutcome.SOLD, TripStatus.CLOSED, 30L},
                new Object[]{TripLineOutcome.RETURNED, TripStatus.CLOSED, 6L},
                new Object[]{TripLineOutcome.BROKEN, TripStatus.CLOSED, 1L},
                new Object[]{TripLineOutcome.MISSING, TripStatus.RETURNED, 1L},
                new Object[]{null, TripStatus.DEPARTED, 2L},
                new Object[]{null, TripStatus.CANCELLED, 9L}));
        SiteTransferUnitRepository siteUnits = mock(SiteTransferUnitRepository.class);
        when(siteUnits.dispatchedBetween(any(), any())).thenReturn(List.<Object[]>of(new Object[]{8L, 6L}));
        when(siteUnits.inTransitBetween(any(), any())).thenReturn(1L);
        TransactionTicketRepository tickets = mock(TransactionTicketRepository.class);
        when(tickets.countByRaisedAtBetweenAndStatusIn(any(), any(), eq(List.of(TicketStatus.values())))).thenReturn(25L);
        when(tickets.countByRaisedAtBetweenAndStatusIn(any(), any(), eq(TicketService.OPEN))).thenReturn(3L);
        when(movements.byTypeBetween(any(), any())).thenReturn(List.of());
        when(movements.leftForBetween(any(), any(), any())).thenReturn(List.<Object[]>of(new Object[]{2L, new BigDecimal("166400.00")}));
        JournalLineRepository journal = mock(JournalLineRepository.class);
        when(journal.balanceAt(AccountKey.INVENTORY, SEPTEMBER.atEndOfMonth())).thenReturn(new BigDecimal("20800000.00"));
        SettingService settings = mock(SettingService.class);
        when(settings.getDecimal(SettingKey.RECONCILIATION_TOLERANCE_PERCENT)).thenReturn(new BigDecimal("0.5"));
        notifier = mock(Notifier.class);
        service = new StockReviewService(movements, days, recons, trips, siteUnits, tickets, journal, mock(StockUnitRepository.class),
                settings, notifier, CLOCK);
        AppUserPrincipal principal = new AppUserPrincipal(4L, "finance1", "finance1", "x", true, true, List.of());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void theMonthAccountsForVehiclesSitesTicketsAndGlassLost() {
        StockReviewService.Month m = service.month(SEPTEMBER);
        assertThat(m.vehicles().loaded()).isEqualTo(40);                    // the cancelled trip's units never left
        assertThat(m.vehicles().getAccounted()).isEqualTo(39);              // sold, back, broken, on the road
        assertThat(m.vehicles().getDifference()).isEqualTo(1);              // the missing one, in its audit case
        assertThat(m.sites().getDifference()).isEqualTo(1);                 // 8 sent, 6 arrived, 1 in transit
        assertThat(m.ticketsRaised()).isEqualTo(25);
        assertThat(m.ticketsOpen()).isEqualTo(3);
        assertThat(m.percent()).isEqualByComparingTo("0.8");                // 166,400 of 20,800,000
        assertThat(m.aboveTolerance()).isTrue();
    }

    @Test
    void anEndedMonthIsSignedOffOnceWithANoteAboveTheTolerance() {
        assertThatThrownBy(() -> service.reviewMonth(YearMonth.of(2026, 10), "ok"))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getMessageKey()).isEqualTo("stockReview.month.notEnded"));
        assertThatThrownBy(() -> service.reviewMonth(SEPTEMBER, null))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getMessageKey()).isEqualTo("stockReview.month.noteRequired"));

        StockReconciliation r = service.reviewMonth(SEPTEMBER, "Two sheets broke at the count");
        assertThat(r.getPeriodStart()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(r.getLoadedUnits()).isEqualTo(40);
        assertThat(r.getAccountedUnits()).isEqualTo(39);
        assertThat(r.getDispatchedUnits()).isEqualTo(8);
        assertThat(r.getArrivedUnits()).isEqualTo(7);
        assertThat(r.getTicketsOpen()).isEqualTo(3);
        assertThat(r.isAboveTolerance()).isTrue();
        assertThat(r.getReviewedBy()).isEqualTo("finance1");
        verify(notifier).holders("VIEW_STOCK_RECONCILIATION", 4L, NotificationKind.TRANSFER, "notify.reconciliation.above",
                "notify.reconciliation.aboveText", "/reports/stock-reconciliation?month=2026-09", "2026-09", "0.80", "0.5");

        when(recons.findByPeriodStart(SEPTEMBER.atDay(1))).thenReturn(Optional.of(r));
        assertThatThrownBy(() -> service.reviewMonth(SEPTEMBER, "again"))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getMessageKey()).isEqualTo("stockReview.month.done"));
    }

    @Test
    void aDayIsSignedOffOnceItHasComeAndOnlyOnce() {
        LocalDate today = LocalDate.now(CLOCK);
        assertThatThrownBy(() -> service.reviewDay(today.plusDays(1), null))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getMessageKey()).isEqualTo("stockReview.day.future"));
        when(movements.byTypeBetween(any(), any())).thenReturn(List.of(
                new Object[]{MovementType.RECEIPT, 12L, new BigDecimal("86.67"), new BigDecimal("1250000")},
                new Object[]{MovementType.SALE, 4L, new BigDecimal("28.89"), new BigDecimal("410000")}));
        StockDayReview review = service.reviewDay(today, "All agree with their tickets");
        assertThat(review.getMovements()).isEqualTo(16);
        assertThat(review.getReviewedBy()).isEqualTo("finance1");
        when(days.findByDay(today)).thenReturn(Optional.of(review));
        assertThatThrownBy(() -> service.reviewDay(today, null))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getMessageKey()).isEqualTo("stockReview.day.done"));
    }
}
