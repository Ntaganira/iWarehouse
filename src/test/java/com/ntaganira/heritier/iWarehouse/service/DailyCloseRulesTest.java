package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.enums.JournalSource;
import com.ntaganira.heritier.iWarehouse.enums.PaymentMethod;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : DailyCloseRulesTest.java
 * - Date      : 2026. 10. 15.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : The pure rules of the daily close and the month's follow-up (M14): the cash count by denomination, working
 *               days, the end-of-day split by channel and method, reconciliation compliance, the movement of receivables
 *               and the days sales outstanding, gaps in the journal register, the reporting calendar.
 * </pre>
 */
class DailyCloseRulesTest {

    private static BigDecimal rwf(String v) {
        return new BigDecimal(v);
    }

    // ---------------------------------------------------------------- the cash count (F-13)

    @Test
    void theCashIsCountedNoteByNote() {
        List<Integer> notes = CashCount.denominations("5000, 2000,1000,500,100,50,20,10,5,1,abc,500");
        assertThat(notes).containsExactly(5000, 2000, 1000, 500, 100, 50, 20, 10, 5, 1);
        Map<Integer, Integer> count = CashCount.of(Map.of(5000, 12, 1000, 3, 100, 0, 5, 7), notes);
        assertThat(CashCount.total(count)).isEqualByComparingTo("63035");
        assertThat(CashCount.format(count)).isEqualTo("5000:12,1000:3,5:7");
        assertThat(CashCount.parse("5000:12,1000:3,5:7")).containsEntry(5000, 12).containsEntry(5, 7).hasSize(3);
        assertThat(CashCount.format(Map.of())).isNull();
    }

    @Test
    void aCountOfANoteNotListedOrBelowZeroIsRefused() {
        List<Integer> notes = CashCount.denominations("5000,1000");
        assertThatThrownBy(() -> CashCount.of(Map.of(200, 1), notes)).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getMessageKey()).isEqualTo("till.count.unknown"));
        assertThatThrownBy(() -> CashCount.of(Map.of(1000, -2), notes)).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getMessageKey()).isEqualTo("till.count.negative"));
    }

    // ---------------------------------------------------------------- working days

    @Test
    void workingDaysSkipTheWeekend() {
        // November 2026 starts on a Sunday: the 1st working day is Monday the 2nd, the 5th Friday the 6th, the 10th the 13th
        assertThat(WorkingDays.nth(YearMonth.of(2026, 11), 1)).isEqualTo(LocalDate.of(2026, 11, 2));
        assertThat(WorkingDays.nth(YearMonth.of(2026, 11), 5)).isEqualTo(LocalDate.of(2026, 11, 6));
        assertThat(WorkingDays.nth(YearMonth.of(2026, 11), 10)).isEqualTo(LocalDate.of(2026, 11, 13));
        // October 2026 starts on a Thursday
        assertThat(WorkingDays.nth(YearMonth.of(2026, 10), 1)).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(WorkingDays.previous(LocalDate.of(2026, 10, 19))).isEqualTo(LocalDate.of(2026, 10, 16));
        assertThat(WorkingDays.onOrAfter(LocalDate.of(2026, 10, 17))).isEqualTo(LocalDate.of(2026, 10, 19));
    }

    // ---------------------------------------------------------------- the end-of-day split (F-12)

    @Test
    void theDaysSalesAreSplitByChannelAndMethod() {
        List<DailyCloses.Sale> sales = List.of(
                new DailyCloses.Sale("COUNTER", Map.of(PaymentMethod.CASH, rwf("10000"), PaymentMethod.MOBILE_MONEY, rwf("5000")), BigDecimal.ZERO),
                new DailyCloses.Sale("TELEPHONE", Map.of(PaymentMethod.CREDIT, rwf("40000")), BigDecimal.ZERO),
                new DailyCloses.Sale("COUNTER", Map.of(PaymentMethod.CASH, rwf("7000")), rwf("6500")),          // a deposit, its balance left
                new DailyCloses.Sale("FIELD", Map.of(PaymentMethod.BANK_TRANSFER, rwf("3000"), PaymentMethod.CARD, rwf("2000")), BigDecimal.ZERO));
        List<DailyCloses.ChannelRow> rows = DailyCloses.byChannel(sales, List.of("COUNTER", "BRANCH", "TELEPHONE", "FIELD"));
        assertThat(rows).extracting(DailyCloses.ChannelRow::channel).containsExactly("COUNTER", "TELEPHONE", "FIELD");
        DailyCloses.ChannelRow counter = rows.get(0);
        assertThat(counter.invoices()).isEqualTo(2);
        assertThat(counter.cash()).isEqualByComparingTo("17000");
        assertThat(counter.momo()).isEqualByComparingTo("5000");
        assertThat(counter.credit()).isEqualByComparingTo("6500");
        assertThat(rows.get(2).other()).isEqualByComparingTo("5000");
        DailyCloses.ChannelRow total = DailyCloses.total(rows);
        assertThat(total.invoices()).isEqualTo(4);
        assertThat(total.getTotal()).isEqualByComparingTo("73500");
        assertThat(total.credit()).isEqualByComparingTo("46500");
        assertThat(DailyCloses.variance(rwf("145008"), rwf("144008"))).isEqualByComparingTo("-1000");
    }

    // ---------------------------------------------------------------- reconciliation compliance (M.16)

    @Test
    void aMonthIsReconciledOnTimeByTheFifthWorkingDay() {
        LocalDate today = LocalDate.of(2026, 12, 10);
        List<ReconciliationCompliance.Done> done = List.of(
                new ReconciliationCompliance.Done(LocalDate.of(2026, 9, 30), LocalDate.of(2026, 10, 5)),    // on time (5th working day: 7 Oct)
                new ReconciliationCompliance.Done(LocalDate.of(2026, 11, 3), LocalDate.of(2026, 11, 9)));   // reaches October, late (due 6 Nov)
        ReconciliationCompliance.Result r = ReconciliationCompliance.of(done, YearMonth.of(2026, 9), YearMonth.of(2026, 11), 5, today);
        assertThat(r.months()).extracting(ReconciliationCompliance.Month::state).containsExactly(
                ReconciliationCompliance.State.MISSING,     // November: due 7 December, nothing reaches 30 November
                ReconciliationCompliance.State.LATE,        // October
                ReconciliationCompliance.State.ON_TIME);    // September
        assertThat(r.due()).isEqualTo(3);
        assertThat(r.onTime()).isEqualTo(1);
        assertThat(r.getPercent()).isEqualByComparingTo("33.3");
        assertThat(r.getMissing()).isEqualTo(1);
        // Before its day, a month is pending and not counted
        ReconciliationCompliance.Result early = ReconciliationCompliance.of(done, YearMonth.of(2026, 11), YearMonth.of(2026, 11), 5,
                LocalDate.of(2026, 12, 4));
        assertThat(early.months().get(0).state()).isEqualTo(ReconciliationCompliance.State.PENDING);
        assertThat(early.getPercent()).isNull();
    }

    // ---------------------------------------------------------------- the movement of receivables (Q.06, Q.08)

    @Test
    void theMovementOfReceivablesAddsUpToTheClosingBalance() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        LocalDate from = LocalDate.of(2026, 10, 1);
        LocalDate to = LocalDate.of(2026, 10, 31);
        List<ReceivableMovement.Entry> entries = List.of(
                new ReceivableMovement.Entry(a, LocalDate.of(2026, 9, 20), JournalSource.SALES_INVOICE, rwf("100000"), BigDecimal.ZERO),
                new ReceivableMovement.Entry(a, LocalDate.of(2026, 10, 3), JournalSource.CUSTOMER_PAYMENT, BigDecimal.ZERO, rwf("60000")),
                new ReceivableMovement.Entry(a, LocalDate.of(2026, 10, 9), JournalSource.SALES_INVOICE, rwf("50000"), BigDecimal.ZERO),
                new ReceivableMovement.Entry(a, LocalDate.of(2026, 10, 12), JournalSource.CREDIT_NOTE, BigDecimal.ZERO, rwf("5000")),
                new ReceivableMovement.Entry(b, LocalDate.of(2026, 10, 15), JournalSource.MOBILE_SALE, rwf("20000"), BigDecimal.ZERO),
                new ReceivableMovement.Entry(b, LocalDate.of(2026, 10, 20), JournalSource.SALES_BALANCE, BigDecimal.ZERO, rwf("8000")),
                new ReceivableMovement.Entry(b, LocalDate.of(2026, 10, 30), JournalSource.RECEIVABLE_WRITE_OFF, BigDecimal.ZERO, rwf("2000")),
                new ReceivableMovement.Entry(b, LocalDate.of(2026, 11, 2), JournalSource.SALES_INVOICE, rwf("99000"), BigDecimal.ZERO));
        List<ReceivableMovement.Row> rows = ReceivableMovement.of(entries, from, to);
        ReceivableMovement.Row ra = rows.get(0);
        assertThat(ra.opening()).isEqualByComparingTo("100000");
        assertThat(ra.newCredit()).isEqualByComparingTo("50000");
        assertThat(ra.collections()).isEqualByComparingTo("60000");
        assertThat(ra.creditNotes()).isEqualByComparingTo("5000");
        assertThat(ra.getClosing()).isEqualByComparingTo("85000");
        ReceivableMovement.Row rb = rows.get(1);
        assertThat(rb.writeOffs()).isEqualByComparingTo("2000");
        assertThat(rb.getClosing()).isEqualByComparingTo("10000");                       // November's sale left out
        ReceivableMovement.Row total = ReceivableMovement.total(rows);
        assertThat(total.getClosing()).isEqualByComparingTo("95000");
        assertThat(total.opening().add(total.newCredit()).subtract(total.collections()).subtract(total.creditNotes())
                .subtract(total.writeOffs()).add(total.other())).isEqualByComparingTo(total.getClosing());

        assertThat(ReceivableMovement.creditSales(entries, from, to)).isEqualByComparingTo("70000");
        assertThat(ReceivableMovement.dso(rwf("95000"), rwf("70000"), 31)).isEqualByComparingTo("42.1");
        assertThat(ReceivableMovement.dso(rwf("95000"), BigDecimal.ZERO, 31)).isNull();
        assertThat(ReceivableMovement.share(rwf("6000"), rwf("95000"))).isEqualByComparingTo("6.3");
        assertThat(ReceivableMovement.share(rwf("6000"), BigDecimal.ZERO)).isEqualByComparingTo("0");
    }

    // ---------------------------------------------------------------- the journal register (U.07)

    @Test
    void aGapIsANumberNoJournalHolds() {
        List<String> month = List.of("JV-WH-2026-000137", "JV-WH-2026-000138", "JV-WH-2026-000141", "JV-WH-2026-000145");
        // 000139 is dated in another month (posted later, backdated): no gap; 000140, 000142 to 000144 are held by none
        List<String> all = List.of("JV-WH-2026-000137", "JV-WH-2026-000138", "JV-WH-2026-000139", "JV-WH-2026-000141", "JV-WH-2026-000145");
        List<String> gaps = JournalRegister.gaps(month, all);
        assertThat(gaps).containsExactly("JV-WH-2026-000140", "JV-WH-2026-000142 to 000144");
        assertThat(JournalRegister.summary(gaps, 500)).isEqualTo("JV-WH-2026-000140, JV-WH-2026-000142 to 000144");
        assertThat(JournalRegister.summary(gaps, 20)).hasSize(20).endsWith("…");
        assertThat(JournalRegister.gaps(List.of("JV-WH-2026-000001", "JV-WH-2026-000002"), List.of())).isEmpty();
        assertThat(JournalRegister.summary(List.of(), 500)).isNull();
    }

    // ---------------------------------------------------------------- the reporting calendar (M.11)

    @Test
    void theCalendarSaysWhatIsDue() {
        LocalTime ten = LocalTime.of(10, 0);
        // Before 10:00 on the 16th the 14th is the last close due; from 10:00, the 15th
        assertThat(ReportCalendar.lastDueClose(LocalDateTime.of(2026, 10, 16, 9, 59), ten)).isEqualTo(LocalDate.of(2026, 10, 14));
        assertThat(ReportCalendar.lastDueClose(LocalDateTime.of(2026, 10, 16, 10, 0), ten)).isEqualTo(LocalDate.of(2026, 10, 15));
        assertThat(ReportCalendar.time("08:30", ten)).isEqualTo(LocalTime.of(8, 30));
        assertThat(ReportCalendar.time("soon", ten)).isEqualTo(ten);
        // Mondays from 08:00
        assertThat(ReportCalendar.weekly(LocalDateTime.of(2026, 10, 19, 8, 0))).contains("WEEKLY:2026-10-19");
        assertThat(ReportCalendar.weekly(LocalDateTime.of(2026, 10, 19, 7, 59))).isEmpty();
        assertThat(ReportCalendar.weekly(LocalDateTime.of(2026, 10, 20, 9, 0))).isEmpty();
        // The month's accounts by the 10th working day (November 2026: the 13th)
        assertThat(ReportCalendar.accountsLate(LocalDate.of(2026, 11, 13), 10)).isEmpty();
        assertThat(ReportCalendar.accountsLate(LocalDate.of(2026, 11, 14), 10)).contains(YearMonth.of(2026, 10));
        // A quarter ended: from the first working day of January, April, July, October
        assertThat(ReportCalendar.quarterEnded(LocalDate.of(2026, 10, 1))).contains("2026-Q3");
        assertThat(ReportCalendar.quarterEnded(LocalDate.of(2027, 1, 4))).contains("2026-Q4");
        assertThat(ReportCalendar.quarterEnded(LocalDate.of(2028, 1, 1))).isEmpty();      // a Saturday: January 2028 starts working on the 3rd
        assertThat(ReportCalendar.quarterEnded(LocalDate.of(2028, 1, 3))).contains("2027-Q4");
        assertThat(ReportCalendar.quarterEnded(LocalDate.of(2026, 11, 2))).isEmpty();
    }
}
