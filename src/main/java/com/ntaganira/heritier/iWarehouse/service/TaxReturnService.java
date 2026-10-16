package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.entity.ExpenseBill;
import com.ntaganira.heritier.iWarehouse.entity.PayRun;
import com.ntaganira.heritier.iWarehouse.enums.AccountKey;
import com.ntaganira.heritier.iWarehouse.enums.ExpenseBillStatus;
import com.ntaganira.heritier.iWarehouse.enums.PayRunStatus;
import com.ntaganira.heritier.iWarehouse.enums.SettingKey;
import com.ntaganira.heritier.iWarehouse.repository.ExpenseBillRepository;
import com.ntaganira.heritier.iWarehouse.repository.JournalLineRepository;
import com.ntaganira.heritier.iWarehouse.repository.PayRunRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : TaxReturnService.java
 * - Date      : 2026. 10. 16.
 * - User      : Hntaganira
 * - Desc      : The month's tax returns at a glance (U.14), each due to RRA or RSSB on the 15th of the next month: VAT (the VAT
 *               report), PAYE and RSSB (the month's pay run), the withholding tax return (each expense bill that withheld tax in
 *               the month by supplier and TIN, less the bills reversed in it; what the account still owes to RRA), and the
 *               corporate income tax estimate (the year's profit to the month's end at the Settings' rate, and the year at the
 *               same pace). Read from the documents and the journals only.
 * </pre>
 */
@Service
@Transactional(readOnly = true)
public class TaxReturnService {

    private static final List<ExpenseBillStatus> WITHHELD = List.of(ExpenseBillStatus.APPROVED, ExpenseBillStatus.REVERSED);
    private static final List<PayRunStatus> POSTED = List.of(PayRunStatus.POSTED, PayRunStatus.PAID);

    private final ExpenseBillRepository billRepo;
    private final PayRunRepository payRunRepo;
    private final JournalLineRepository lineRepo;
    private final VatReportService vatReports;
    private final FinancialStatementService statements;
    private final SettingService settings;
    private final Clock clock;

    public TaxReturnService(ExpenseBillRepository billRepo, PayRunRepository payRunRepo, JournalLineRepository lineRepo,
                            VatReportService vatReports, FinancialStatementService statements, SettingService settings, Clock clock) {
        this.billRepo = billRepo;
        this.payRunRepo = payRunRepo;
        this.lineRepo = lineRepo;
        this.vatReports = vatReports;
        this.statements = statements;
        this.settings = settings;
        this.clock = clock;
    }

    /** A line of the withholding tax return: a bill that withheld tax, or its reversal (negative). */
    public record WhtLine(ExpenseBill bill, boolean reversal) {

        public LocalDate getDate() {
            return reversal ? bill.getReversalDate() : bill.getBillDate();
        }

        public BigDecimal getNet() {
            return reversal ? bill.getNetAmount().negate() : bill.getNetAmount();
        }

        public BigDecimal getAmount() {
            return reversal ? bill.getWhtAmount().negate() : bill.getWhtAmount();
        }
    }

    /** The corporate income tax estimate: the year's profit to the month's end, the tax on it, and the year at the same pace. */
    public record Cit(LocalDate from, LocalDate to, BigDecimal profit, BigDecimal rate, BigDecimal estimate, BigDecimal yearProfit,
                      BigDecimal yearEstimate) {
    }

    public record Report(YearMonth month, VatReportService.Report vat, PayRun payRun, List<WhtLine> wht, BigDecimal whtOwed, BigDecimal whtRate,
                         Cit cit) {

        public LocalDate getFrom() {
            return month.atDay(1);
        }

        public LocalDate getTo() {
            return month.atEndOfMonth();
        }

        /** Returns are due on the 15th of the next month. */
        public LocalDate getDueDate() {
            return month.plusMonths(1).atDay(CashFlowService.TAX_DAY);
        }

        public BigDecimal getWhtNet() {
            return wht.stream().map(WhtLine::getNet).reduce(BigDecimal.ZERO, BigDecimal::add);
        }

        public BigDecimal getWhtTotal() {
            return wht.stream().map(WhtLine::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        }
    }

    /** The months to choose from: this one and the eleven before it. */
    public List<YearMonth> months() {
        YearMonth now = YearMonth.from(today());
        List<YearMonth> months = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            months.add(now.minusMonths(i));
        }
        return months;
    }

    public Report report(YearMonth month) {
        LocalDate from = month.atDay(1);
        LocalDate to = month.atEndOfMonth();
        List<WhtLine> wht = new ArrayList<>();
        billRepo.findByWhtAmountGreaterThanAndBillDateBetweenAndStatusInOrderByBillDateAscNumberAsc(BigDecimal.ZERO, from, to, WITHHELD)
                .forEach(b -> wht.add(new WhtLine(b, false)));
        billRepo.findByWhtAmountGreaterThanAndReversalDateBetweenAndStatusOrderByReversalDateAscNumberAsc(BigDecimal.ZERO, from, to,
                ExpenseBillStatus.REVERSED).forEach(b -> wht.add(new WhtLine(b, true)));
        wht.sort((a, b) -> {
            int bySupplier = a.bill().getSupplier().getName().compareToIgnoreCase(b.bill().getSupplier().getName());
            return bySupplier != 0 ? bySupplier : a.getDate().compareTo(b.getDate());
        });
        BigDecimal owed = lineRepo.balanceAt(AccountKey.WHT_PAYABLE, to).negate();
        PayRun run = payRunRepo.findFirstByPeriodAndStatusIn(from, POSTED).orElse(null);
        return new Report(month, vatReports.report(month), run, wht, owed, settings.getDecimal(SettingKey.TAX_WHT_RATE), cit(month));
    }

    private Cit cit(YearMonth month) {
        LocalDate from = month.atDay(1).withDayOfYear(1);
        LocalDate to = month.atEndOfMonth();
        BigDecimal rate = settings.getDecimal(SettingKey.TAX_CIT_RATE);
        BigDecimal profit = statements.incomeStatement(from, to).getNetProfit();
        BigDecimal yearProfit = profit.multiply(BigDecimal.valueOf(12)).divide(BigDecimal.valueOf(month.getMonthValue()), 0, RoundingMode.HALF_UP);
        return new Cit(from, to, profit, rate, taxOn(profit, rate), yearProfit, taxOn(yearProfit, rate));
    }

    /** The tax on a profit at a rate (%), whole francs; nothing on a loss. */
    static BigDecimal taxOn(BigDecimal profit, BigDecimal rate) {
        return profit.signum() <= 0 ? BigDecimal.ZERO : profit.multiply(rate).divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP);
    }

    public LocalDate today() {
        return LocalDate.now(clock);
    }
}
