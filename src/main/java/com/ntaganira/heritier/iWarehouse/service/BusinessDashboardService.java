package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.enums.ExpenseBillStatus;
import com.ntaganira.heritier.iWarehouse.enums.RequisitionStatus;

import com.ntaganira.heritier.iWarehouse.dto.Trend;
import com.ntaganira.heritier.iWarehouse.entity.Account;
import com.ntaganira.heritier.iWarehouse.enums.AccountKey;
import com.ntaganira.heritier.iWarehouse.enums.AdjustmentStatus;
import com.ntaganira.heritier.iWarehouse.enums.CreditNoteRequestStatus;
import com.ntaganira.heritier.iWarehouse.enums.ManualJournalStatus;
import com.ntaganira.heritier.iWarehouse.enums.PaymentRequestStatus;
import com.ntaganira.heritier.iWarehouse.enums.PriceChangeStatus;
import com.ntaganira.heritier.iWarehouse.enums.DeliveryNoteStatus;
import com.ntaganira.heritier.iWarehouse.enums.SiteTransferStatus;
import com.ntaganira.heritier.iWarehouse.enums.SupplierApprovalStatus;
import com.ntaganira.heritier.iWarehouse.enums.TicketStatus;
import com.ntaganira.heritier.iWarehouse.enums.PurchaseOrderStatus;
import com.ntaganira.heritier.iWarehouse.enums.SaleApprovalStatus;
import com.ntaganira.heritier.iWarehouse.enums.TillStatus;
import com.ntaganira.heritier.iWarehouse.enums.SettingKey;
import com.ntaganira.heritier.iWarehouse.enums.TripStatus;
import com.ntaganira.heritier.iWarehouse.enums.VehicleCostStatus;
import com.ntaganira.heritier.iWarehouse.enums.WriteOffStatus;
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
 *               floats from the ledger), receivables and payables, glass to reorder, requests waiting for approval, invoices not signed by EBM (TAX-03) and
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
    private final EbmService ebmService;
    private final FleetAlertService fleetAlerts;
    private final TripRepository tripRepo;
    private final AuditCaseService auditCases;
    private final FloatService floats;
    private final VehicleCostRepository vehicleCostRepo;
    private final ServicePlanService servicePlans;
    private final Clock clock;
    private final CreditNoteRequestRepository creditNoteRequestRepo;
    private final ReceivableWriteOffRepository writeOffRepo;
    private final PurchaseOrderRepository purchaseOrderRepo;
    private final SupplierPaymentRequestRepository paymentRequestRepo;
    private final CreditApplicationRepository creditApplicationRepo;
    private final PriceChangeRepository priceChangeRepo;
    private final SupplierRepository supplierRepo;
    private final TransactionTicketRepository ticketRepo;
    private final SiteTransferRepository siteTransferRepo;
    private final DeliveryNoteRepository deliveryNoteRepo;
    private final SettingService settings;
    private final com.ntaganira.heritier.iWarehouse.repository.DamageReportRepository damageRepo;
    private final com.ntaganira.heritier.iWarehouse.repository.StockVarianceRepository varianceRepo;
    private final com.ntaganira.heritier.iWarehouse.repository.ExpenseBillRepository expenseBillRepo;
    private final com.ntaganira.heritier.iWarehouse.repository.PurchaseRequisitionRepository requisitionRepo;
    private final com.ntaganira.heritier.iWarehouse.repository.PettyCashVoucherRepository voucherRepo;
    private final com.ntaganira.heritier.iWarehouse.repository.StaffAdvanceRepository advanceRepo;
    private final PettyCashService pettyCash;

    public BusinessDashboardService(SalesInvoiceRepository invoiceRepo, SalesReportService salesReports, StockSummaryService stockSummary,
                                    JournalService journalService, CustomerAccountService customerAccounts,
                                    SupplierAccountService supplierAccounts, SaleApprovalRepository saleApprovalRepo,
                                    StockAdjustmentRepository adjustmentRepo, ManualJournalRepository manualJournalRepo,
                                    TillSessionRepository tillRepo, EbmService ebmService, FleetAlertService fleetAlerts,
                                    TripRepository tripRepo, AuditCaseService auditCases, FloatService floats,
                                    VehicleCostRepository vehicleCostRepo, ServicePlanService servicePlans, Clock clock,
                                    CreditNoteRequestRepository creditNoteRequestRepo, ReceivableWriteOffRepository writeOffRepo,
                                    PurchaseOrderRepository purchaseOrderRepo, SupplierPaymentRequestRepository paymentRequestRepo,
                                    CreditApplicationRepository creditApplicationRepo, PriceChangeRepository priceChangeRepo,
                                    SupplierRepository supplierRepo, TransactionTicketRepository ticketRepo,
                                    SiteTransferRepository siteTransferRepo, DeliveryNoteRepository deliveryNoteRepo, SettingService settings,
                                    com.ntaganira.heritier.iWarehouse.repository.DamageReportRepository damageRepo,
                                    com.ntaganira.heritier.iWarehouse.repository.StockVarianceRepository varianceRepo,
                                    com.ntaganira.heritier.iWarehouse.repository.ExpenseBillRepository expenseBillRepo,
                                    com.ntaganira.heritier.iWarehouse.repository.PurchaseRequisitionRepository requisitionRepo,
                                    com.ntaganira.heritier.iWarehouse.repository.PettyCashVoucherRepository voucherRepo,
                                    com.ntaganira.heritier.iWarehouse.repository.StaffAdvanceRepository advanceRepo, PettyCashService pettyCash) {
        this.expenseBillRepo = expenseBillRepo;
        this.requisitionRepo = requisitionRepo;
        this.voucherRepo = voucherRepo;
        this.advanceRepo = advanceRepo;
        this.pettyCash = pettyCash;
        this.damageRepo = damageRepo;
        this.varianceRepo = varianceRepo;
        this.deliveryNoteRepo = deliveryNoteRepo;
        this.settings = settings;
        this.ticketRepo = ticketRepo;
        this.siteTransferRepo = siteTransferRepo;
        this.creditApplicationRepo = creditApplicationRepo;
        this.priceChangeRepo = priceChangeRepo;
        this.supplierRepo = supplierRepo;
        this.creditNoteRequestRepo = creditNoteRequestRepo;
        this.writeOffRepo = writeOffRepo;
        this.purchaseOrderRepo = purchaseOrderRepo;
        this.paymentRequestRepo = paymentRequestRepo;
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
        this.ebmService = ebmService;
        this.fleetAlerts = fleetAlerts;
        this.tripRepo = tripRepo;
        this.auditCases = auditCases;
        this.floats = floats;
        this.vehicleCostRepo = vehicleCostRepo;
        this.servicePlans = servicePlans;
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
                       EbmService.Backlog ebm, int fleetPapers, long vehiclesOnTheRoad, long auditCases, long floatsToClear,
                       BigDecimal floatsToClearAmount, long vehicleCosts, int servicesDue, List<String> chartDays, List<BigDecimal> chartNet,
                       long creditNotes, long writeOffs, long purchaseOrders, long supplierPayments, long creditApplications,
                       long priceChanges, long suppliers, long tickets, long siteTransfers, long deliveryNotes, long loadRequests,
                       long damageReports, long variances, long expenseBills, long requisitions, long toRatify, long pettyCashVouchers,
                       long advances, long advancesOverdue, long pettyCashLow) {

        public long getInvoicesToday() {
            return channels.stream().mapToLong(Channel::invoices).sum();
        }

        public long getWaiting() {
            return saleApprovals + adjustments + manualJournals + vehicleCosts + creditNotes + writeOffs + purchaseOrders + supplierPayments
                    + creditApplications + priceChanges + suppliers + tickets + siteTransfers + deliveryNotes + loadRequests + damageReports + variances
                    + expenseBills + requisitions + toRatify + pettyCashVouchers + advances;
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

        List<FloatService.ToClear> toClear = floats.toClear();
        LocalDate first = today.minusDays(CHART_DAYS - 1);
        Map<LocalDate, BigDecimal> perDay = salesReports.netPerDay(first, today);

        return new View(today, salesToday, trend, channels, salesReports.day(today),
                salesReports.report(today, today, SalesAnalysis.GroupBy.SALESPERSON, null, null, null).rows(),
                stockSummary.summary(StockSummary.GroupBy.PRODUCT, null).total(), cash, cashTotal,
                receivables.total(), overdue, receivables.customers().size(), payables.total(), payables.suppliers().size(),
                stockSummary.reorder(), saleApprovalRepo.countByStatusIn(SaleApprovalStatus.WAITING),
                adjustmentRepo.countByStatus(AdjustmentStatus.PENDING_APPROVAL), manualJournalRepo.countByStatus(ManualJournalStatus.PENDING_APPROVAL),
                tillRepo.countByStatus(TillStatus.OPEN), ebmService.backlog(), fleetAlerts.dueCount(),
                tripRepo.countByStatus(TripStatus.DEPARTED), auditCases.unresolvedCount(), toClear.size(),
                toClear.stream().map(FloatService.ToClear::expected).reduce(BigDecimal.ZERO, BigDecimal::add),
                vehicleCostRepo.countByStatus(VehicleCostStatus.PENDING_APPROVAL), servicePlans.dueCount(),
                perDay.keySet().stream().map(LocalDate::toString).toList(), new ArrayList<>(perDay.values()),
                creditNoteRequestRepo.countByStatusIn(List.of(CreditNoteRequestStatus.PENDING_REVIEW, CreditNoteRequestStatus.PENDING_APPROVAL)),
                writeOffRepo.countByStatusIn(List.of(WriteOffStatus.PENDING_REVIEW, WriteOffStatus.PENDING_APPROVAL)),
                purchaseOrderRepo.countByStatus(PurchaseOrderStatus.PENDING_APPROVAL),
                paymentRequestRepo.countByStatusIn(List.of(PaymentRequestStatus.PREPARED, PaymentRequestStatus.AUTHORISED)),
                creditApplicationRepo.countByStatusIn(CreditApplicationService.WAITING),
                priceChangeRepo.countByStatus(PriceChangeStatus.PENDING),
                supplierRepo.countByApprovalStatus(SupplierApprovalStatus.PENDING),
                ticketRepo.countByStatusIn(List.of(TicketStatus.RAISED, TicketStatus.VERIFIED, TicketStatus.COUNTERSIGNED)),
                siteTransferRepo.countByStatusIn(List.of(SiteTransferStatus.REQUESTED)),
                deliveryNoteRepo.countByStatus(DeliveryNoteStatus.DRAFT),
                settings.getBoolean(SettingKey.FLEET_LOAD_APPROVAL) ? tripRepo.countByStatusAndLoadApprovedAtIsNull(TripStatus.PLANNED) : 0,
                damageRepo.countByStatusIn(List.of(com.ntaganira.heritier.iWarehouse.enums.DamageStatus.REPORTED,
                        com.ntaganira.heritier.iWarehouse.enums.DamageStatus.INSPECTED)),
                varianceRepo.countByStatusIn(List.of(com.ntaganira.heritier.iWarehouse.enums.VarianceStatus.OPEN,
                        com.ntaganira.heritier.iWarehouse.enums.VarianceStatus.ESCALATED)),
                expenseBillRepo.countByStatusIn(List.of(ExpenseBillStatus.SUBMITTED, ExpenseBillStatus.RECOMMENDED, ExpenseBillStatus.REVIEWED)),
                requisitionRepo.countByStatus(RequisitionStatus.SUBMITTED),
                expenseBillRepo.findByEmergencyTrueAndStatusAndRatifiedAtIsNullOrderByDecidedAtAsc(ExpenseBillStatus.APPROVED).size(),
                voucherRepo.countByStatus(com.ntaganira.heritier.iWarehouse.enums.PettyCashVoucherStatus.SUBMITTED),
                advanceRepo.countByStatus(com.ntaganira.heritier.iWarehouse.enums.StaffAdvanceStatus.SUBMITTED),
                advanceRepo.findByStatusAndDueOnBeforeOrderByDueOnAsc(com.ntaganira.heritier.iWarehouse.enums.StaffAdvanceStatus.PAID, today).size(),
                pettyCash.lowCount());
    }

    private static long whole(BigDecimal amount) {
        return amount.setScale(0, RoundingMode.HALF_UP).longValueExact();
    }
}
