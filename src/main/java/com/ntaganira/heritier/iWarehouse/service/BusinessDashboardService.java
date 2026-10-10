package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.dto.Trend;
import com.ntaganira.heritier.iWarehouse.entity.Account;
import com.ntaganira.heritier.iWarehouse.enums.AccountKey;
import com.ntaganira.heritier.iWarehouse.enums.AdjustmentStatus;
import com.ntaganira.heritier.iWarehouse.enums.ManualJournalStatus;
import com.ntaganira.heritier.iWarehouse.enums.SaleApprovalStatus;
import com.ntaganira.heritier.iWarehouse.enums.TillStatus;
import com.ntaganira.heritier.iWarehouse.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : BusinessDashboardService.java
 * - Date      : 2026. 10. 10.
 * - User      : Hntaganira
 * - Desc      : The owner's dashboard (RPT-01): today's sales by channel and salesperson against the same time yesterday,
 *               the gross margin, the stock value at MAC, the cash position (tills, vault, bank, mobile money, driver
 *               floats from the ledger), receivables and payables, glass to reorder, requests waiting for approval and
 *               open tills, and the net sales of the last 30 days. Vehicles' sales, open audit cases and unsigned invoices
 *               join with the fleet, end of day and EBM milestones.
 * </pre>
 */
@Service
@Transactional(readOnly = true)
public class BusinessDashboardService {

    /** Days of net sales on the chart. */
    public static final int CHART_DAYS = 30;

    /** The accounts that hold money, in this order. */
    static final List<AccountKey> CASH_ACCOUNTS = List.of(AccountKey.CASH, AccountKey.CASH_VAULT, AccountKey.BANK, AccountKey.MOBILE_MONEY,
            AccountKey.DRIVER_FLOAT);

    private final SalesInvoiceRepository invoiceRepo;
    private final SalesReportService salesReports;
    private final StockSummaryService stockSummary;
    private final JournalService journalService;
    private final CustomerAccountService customerAccounts;
    private final SupplierAccountService supplierAccounts;
    private final SaleApprovalRepository saleApprovalRepo;
    private final StockAdjustmentRepository adjustmentRepo;
    private final ManualJournalRepository manualJournalRepo;
    private final TillSessionRepository tillRepo;
    private final Clock clock;

    public BusinessDashboardService(SalesInvoiceRepository invoiceRepo, SalesReportService salesReports, StockSummaryService stockSummary,
                                    JournalService journalService, CustomerAccountService customerAccounts,
                                    SupplierAccountService supplierAccounts, SaleApprovalRepository saleApprovalRepo,
                                    StockAdjustmentRepository adjustmentRepo, ManualJournalRepository manualJournalRepo,
                                    TillSessionRepository tillRepo, Clock clock) {
        this.invoiceRepo = invoiceRepo;
        this.salesReports = salesReports;
        this.stockSummary = stockSummary;
        this.journalService = journalService;
        this.customerAccounts = customerAccounts;
        this.supplierAccounts = supplierAccounts;
        this.saleApprovalRepo = saleApprovalRepo;
        this.adjustmentRepo = adjustmentRepo;
        this.manualJournalRepo = manualJournalRepo;
        this.tillRepo = tillRepo;
        this.clock = clock;
    }

    /** Sales of a channel today: invoices, net (VAT out) and total (VAT in). */
    public record Channel(String channel, long invoices, BigDecimal net, BigDecimal total) {
    }

    /** A money account and its balance in the ledger today. */
    public record CashLine(Account account, BigDecimal balance) {
    }

    public record View(LocalDate today, BigDecimal salesToday, Trend salesTrend, List<Channel> channels, SalesAnalysis.Row day,
                       List<SalesAnalysis.Row> bySalesperson, StockSummary.Row stock, List<CashLine> cash, BigDecimal cashTotal,
                       BigDecimal receivable, BigDecimal overdue, long customersOwing, BigDecimal payable, long suppliersOwed,
                       List<StockSummary.Reorder> reorder, long saleApprovals, long adjustments, long manualJournals, long openTills,
                       List<String> chartDays, List<BigDecimal> chartNet) {

        public long getInvoicesToday() {
            return channels.stream().mapToLong(Channel::invoices).sum();
        }

        public long getWaiting() {
            return saleApprovals + adjustments + manualJournals;
        }
    }

    public View load() {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDate today = now.toLocalDate();
        LocalDateTime todayStart = today.atStartOfDay();

        List<Channel> channels = new ArrayList<>();
        BigDecimal salesToday = BigDecimal.ZERO;
        for (Object[] r : invoiceRepo.perChannel(todayStart, now)) {
            Channel c = new Channel((String) r[0], ((Number) r[1]).longValue(), (BigDecimal) r[2], (BigDecimal) r[3]);
            channels.add(c);
            salesToday = salesToday.add(c.net());
        }
        BigDecimal yesterday = invoiceRepo.perChannel(todayStart.minusDays(1), now.minusDays(1)).stream()
                .map(r -> (BigDecimal) r[2]).reduce(BigDecimal.ZERO, BigDecimal::add);
        Trend trend = new Trend(whole(salesToday), whole(yesterday));

        Map<AccountKey, CashLine> byKey = new EnumMap<>(AccountKey.class);
        for (TrialBalance.Row row : journalService.trialBalance(today).rows()) {
            AccountKey key = row.account().getSystemKey();
            if (key != null && CASH_ACCOUNTS.contains(key)) {
                byKey.put(key, new CashLine(row.account(), row.getNet()));
            }
        }
        List<CashLine> cash = CASH_ACCOUNTS.stream().filter(byKey::containsKey).map(byKey::get).toList();
        BigDecimal cashTotal = cash.stream().map(CashLine::balance).reduce(BigDecimal.ZERO, BigDecimal::add);

        CustomerAccountService.Receivables receivables = customerAccounts.receivables();
        BigDecimal overdue = receivables.totals().entrySet().stream().filter(e -> e.getKey() != Ageing.Bucket.NOT_DUE)
                .map(Map.Entry::getValue).reduce(BigDecimal.ZERO, BigDecimal::add);
        SupplierAccountService.PayablesReport payables = supplierAccounts.payables();

        LocalDate first = today.minusDays(CHART_DAYS - 1);
        Map<LocalDate, BigDecimal> perDay = salesReports.netPerDay(first, today);

        return new View(today, salesToday, trend, channels, salesReports.day(today),
                salesReports.report(today, today, SalesAnalysis.GroupBy.SALESPERSON, null, null, null).rows(),
                stockSummary.summary(StockSummary.GroupBy.PRODUCT, null).total(), cash, cashTotal,
                receivables.total(), overdue, receivables.customers().size(), payables.total(), payables.suppliers().size(),
                stockSummary.reorder(), saleApprovalRepo.countByStatus(SaleApprovalStatus.PENDING),
                adjustmentRepo.countByStatus(AdjustmentStatus.PENDING_APPROVAL), manualJournalRepo.countByStatus(ManualJournalStatus.PENDING_APPROVAL),
                tillRepo.countByStatus(TillStatus.OPEN),
                perDay.keySet().stream().map(LocalDate::toString).toList(), new ArrayList<>(perDay.values()));
    }

    private static long whole(BigDecimal amount) {
        return amount.setScale(0, RoundingMode.HALF_UP).longValueExact();
    }
}
