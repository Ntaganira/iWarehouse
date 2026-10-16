package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.enums.AccountKey;
import com.ntaganira.heritier.iWarehouse.repository.JournalLineRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : ManagementPackService.java
 * - Date      : 2026. 10. 16.
 * - User      : Hntaganira
 * - Desc      : The monthly management accounts pack (BP 13.1, Table 6; T.10) in one piece: the income statement of the
 *               month and of the year to its end, the balance sheet at its end, the Inventory account against the stock
 *               valuation, the receivables and payables ageing and the stock position as they stand today, the month's
 *               sales by territory and by channel, its collections by territory and its inventory KPIs. Each part comes
 *               from the service that owns it, so the pack always agrees with the reports it gathers.
 * </pre>
 */
@Service
@Transactional(readOnly = true)
public class ManagementPackService {

    private final FinancialStatementService statementService;
    private final CustomerAccountService customerAccountService;
    private final SupplierAccountService supplierAccountService;
    private final StockSummaryService stockSummaryService;
    private final SalesReportService salesReportService;
    private final SalesControlReportService salesControlService;
    private final InventoryKpiService kpiService;
    private final JournalLineRepository journalLineRepo;
    private final Clock clock;

    public ManagementPackService(FinancialStatementService statementService, CustomerAccountService customerAccountService,
                                 SupplierAccountService supplierAccountService, StockSummaryService stockSummaryService,
                                 SalesReportService salesReportService, SalesControlReportService salesControlService, InventoryKpiService kpiService,
                                 JournalLineRepository journalLineRepo, Clock clock) {
        this.statementService = statementService;
        this.customerAccountService = customerAccountService;
        this.supplierAccountService = supplierAccountService;
        this.stockSummaryService = stockSummaryService;
        this.salesReportService = salesReportService;
        this.salesControlService = salesControlService;
        this.kpiService = kpiService;
        this.journalLineRepo = journalLineRepo;
        this.clock = clock;
    }

    public record Pack(YearMonth month, LocalDate from, LocalDate to, LocalDate today, FinancialStatements.IncomeStatement income,
                       FinancialStatements.IncomeStatement yearToDate, FinancialStatements.BalanceSheet balance, BigDecimal inventoryAccount,
                       CustomerAccountService.Receivables receivables, SupplierAccountService.PayablesReport payables, StockSummaryService.View stock,
                       SalesReportService.Report byTerritory, SalesReportService.Report byChannel, SalesControlReportService.Collections collections,
                       InventoryKpiService.Measure kpis) {
    }

    public LocalDate today() {
        return LocalDate.now(clock);
    }

    /** The latest month a pack is made for: the month just ended. */
    public YearMonth lastMonth() {
        return YearMonth.from(today()).minusMonths(1);
    }

    public Pack pack(YearMonth month) {
        LocalDate today = today();
        LocalDate from = month.atDay(1);
        LocalDate to = month.atEndOfMonth().isAfter(today) ? today : month.atEndOfMonth();
        return new Pack(month, from, to, today, statementService.incomeStatement(from, to), statementService.incomeStatement(month.atDay(1).withDayOfYear(1), to),
                statementService.balanceSheet(to), journalLineRepo.balanceAt(AccountKey.INVENTORY, to), customerAccountService.receivables(),
                supplierAccountService.payables(), stockSummaryService.summary(StockSummary.GroupBy.PRODUCT, null),
                salesReportService.report(from, to, SalesAnalysis.GroupBy.TERRITORY, null, null, null),
                salesReportService.report(from, to, SalesAnalysis.GroupBy.CHANNEL, null, null, null),
                salesControlService.collections(from, to, CollectionAnalysis.GroupBy.TERRITORY), kpiService.measure(from, month.atEndOfMonth()));
    }
}
