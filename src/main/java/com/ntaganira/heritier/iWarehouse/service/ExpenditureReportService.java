package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.entity.*;
import com.ntaganira.heritier.iWarehouse.enums.*;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : ExpenditureReportService.java
 * - Date      : 2026. 10. 16.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : The expenditure report of a period (CMP 10, F-15 A; T.05, T.06, T.14, C.04): operating spending from the
 *               journals (expense accounts other than the cost of stock, FX and bad debts) by cost centre, location and
 *               account, capital spending (Fixed Assets), what no cost centre names, what was spent by manual journal
 *               outside the expenditure workflow; the expense bills of the period by status, emergencies awaiting the
 *               Board; the indicators (PO compliance: orders and purchase bills that needed a requisition and cited one;
 *               spend with suppliers on the register); and orders and bills to one supplier split under a threshold
 *               (SplitPurchases). Reads documents and journals only.
 * </pre>
 */
@Service
@Transactional(readOnly = true)
public class ExpenditureReportService {

    /** Expense accounts that are not spending: the cost of stock, FX differences, bad debts, the till's over and short. */
    static final Set<AccountKey> NOT_SPENDING = EnumSet.of(AccountKey.COGS, AccountKey.SPOILAGE, AccountKey.STOCK_ADJUSTMENT,
            AccountKey.STOCK_REVALUATION, AccountKey.FX_GAIN_LOSS, AccountKey.FX_UNREALISED, AccountKey.BAD_DEBTS, AccountKey.CASH_OVER_SHORT,
            AccountKey.DEPRECIATION, AccountKey.ASSET_DISPOSAL);
    private static final List<ExpenseBillStatus> COUNTED = List.of(ExpenseBillStatus.SUBMITTED, ExpenseBillStatus.RECOMMENDED,
            ExpenseBillStatus.REVIEWED, ExpenseBillStatus.APPROVED, ExpenseBillStatus.REVERSED);
    private static final List<PurchaseOrderStatus> COMMITTED = List.of(PurchaseOrderStatus.PENDING_APPROVAL, PurchaseOrderStatus.ORDERED,
            PurchaseOrderStatus.PARTIALLY_RECEIVED, PurchaseOrderStatus.RECEIVED, PurchaseOrderStatus.CLOSED);

    private final JournalLineRepository lineRepo;
    private final ExpenseBillRepository billRepo;
    private final PurchaseOrderRepository orderRepo;
    private final CostCentreRepository costCentreRepo;
    private final StockService stockService;
    private final PurchaseOrderService orderService;
    private final RequisitionService requisitions;
    private final StaffAdvanceService advances;
    private final PettyCashService pettyCash;
    private final SupplierInvoiceRepository supplierInvoiceRepo;
    private final SettingService settings;
    private final Clock clock;

    public ExpenditureReportService(JournalLineRepository lineRepo, ExpenseBillRepository billRepo, PurchaseOrderRepository orderRepo,
                                    CostCentreRepository costCentreRepo, StockService stockService, PurchaseOrderService orderService,
                                    RequisitionService requisitions, StaffAdvanceService advances, PettyCashService pettyCash,
                                    SupplierInvoiceRepository supplierInvoiceRepo, SettingService settings, Clock clock) {
        this.lineRepo = lineRepo;
        this.billRepo = billRepo;
        this.orderRepo = orderRepo;
        this.costCentreRepo = costCentreRepo;
        this.stockService = stockService;
        this.orderService = orderService;
        this.requisitions = requisitions;
        this.advances = advances;
        this.pettyCash = pettyCash;
        this.supplierInvoiceRepo = supplierInvoiceRepo;
        this.settings = settings;
        this.clock = clock;
    }

    /** A group of spending: its key, code, name and amount. */
    public record Row(String key, String code, String name, BigDecimal amount) {
    }

    /** The indicators (CMP 10): purchases that needed a requisition and how many cited one, spend on the register. */
    public record Indicators(int purchasesNeeding, int withRequisition, BigDecimal spend, BigDecimal onRegister, BigDecimal manualSpend,
                             BigDecimal untagged, BigDecimal operating) {

        public BigDecimal getPoCompliance() {
            return purchasesNeeding == 0 ? null : InventoryKpis.percent(withRequisition, purchasesNeeding);
        }

        public BigDecimal getOnRegisterPercent() {
            return spend.signum() <= 0 ? null : onRegister.multiply(BigDecimal.valueOf(100)).divide(spend, 1, RoundingMode.HALF_UP);
        }

        public BigDecimal getUntaggedPercent() {
            return operating.signum() <= 0 ? null : untagged.multiply(BigDecimal.valueOf(100)).divide(operating, 1, RoundingMode.HALF_UP);
        }
    }

    /**
     * The report: spending by cost centre, location and account, the bills of the period, the indicators, split purchases; staff
     * advances due in the period retired and retired on time (CMP 10), the supplier invoices of the period (each bills posted goods
     * receipts at their value: the three-way match), and the petty cash funds today.
     */
    public record Report(LocalDate from, LocalDate to, List<Row> byCentre, List<Row> byLocation, List<Row> byAccount, BigDecimal operating,
                         BigDecimal capital, Map<ExpenseBillStatus, Long> bills, long toRatify, Indicators indicators, List<SplitPurchases.Flag> splits,
                         Advances.Rates advances, long supplierInvoices, List<PettyCashService.FundRow> funds) {
    }

    public LocalDate today() {
        return LocalDate.now(clock);
    }

    public Report report(LocalDate from, LocalDate to) {
        Map<UUID, CostCentre> centres = costCentreRepo.findAll().stream().collect(Collectors.toMap(CostCentre::getId, Function.identity()));
        Map<UUID, Location> places = stockService.locationsById();
        Map<String, BigDecimal> byCentre = new LinkedHashMap<>();
        Map<String, BigDecimal> byLocation = new LinkedHashMap<>();
        Map<String, String[]> accounts = new LinkedHashMap<>();
        Map<String, BigDecimal> byAccount = new LinkedHashMap<>();
        BigDecimal operating = BigDecimal.ZERO, capital = BigDecimal.ZERO, untagged = BigDecimal.ZERO, manual = BigDecimal.ZERO;
        for (Object[] r : lineRepo.expenseLines(from, to)) {
            AccountKey key = (AccountKey) r[2];
            BigDecimal amount = (BigDecimal) r[6];
            if (key == AccountKey.FIXED_ASSETS) {
                if (r[5] == JournalSource.EXPENSE_BILL) {
                    capital = capital.add(amount);
                }
                continue;
            }
            if (key != null && NOT_SPENDING.contains(key)) {
                continue;
            }
            operating = operating.add(amount);
            String centre = r[3] == null ? "" : r[3].toString();
            String location = r[4] == null ? "" : r[4].toString();
            byCentre.merge(centre, amount, BigDecimal::add);
            byLocation.merge(location, amount, BigDecimal::add);
            accounts.putIfAbsent((String) r[0], new String[]{(String) r[0], (String) r[1]});
            byAccount.merge((String) r[0], amount, BigDecimal::add);
            if (r[3] == null) {
                untagged = untagged.add(amount);
            }
            if (r[5] == JournalSource.MANUAL_JOURNAL) {
                manual = manual.add(amount);
            }
        }
        List<Row> centreRows = rows(byCentre, k -> {
            CostCentre c = k.isEmpty() ? null : centres.get(UUID.fromString(k));
            return c == null ? new String[]{"", null} : new String[]{c.getCode(), c.getName()};
        });
        List<Row> locationRows = rows(byLocation, k -> {
            Location l = k.isEmpty() ? null : places.get(UUID.fromString(k));
            return l == null ? new String[]{"", null} : new String[]{l.getCode(), l.getName()};
        });
        List<Row> accountRows = rows(byAccount, accounts::get);
        accountRows.sort(Comparator.comparing(Row::code));

        // The bills and orders of the period: their state, the indicators, splitting
        List<ExpenseBill> bills = billRepo.findByBillDateBetweenAndStatusInOrderByBillDateAsc(from, to, List.of(ExpenseBillStatus.values()));
        Map<ExpenseBillStatus, Long> byStatus = new EnumMap<>(ExpenseBillStatus.class);
        bills.forEach(b -> byStatus.merge(b.getStatus(), 1L, Long::sum));
        int needing = 0, cited = 0;
        BigDecimal spend = BigDecimal.ZERO, onRegister = BigDecimal.ZERO;
        int window = settings.getInt(SettingKey.EXPENSE_SPLIT_DAYS);
        List<SplitPurchases.Purchase> purchases = new ArrayList<>();
        for (ExpenseBill b : billRepo.findByBillDateBetweenAndStatusInOrderByBillDateAsc(from.minusDays(window), to, COUNTED)) {
            purchases.add(new SplitPurchases.Purchase(b.getNumber(), "/expenses/" + b.getId(), b.getSupplier().getId(), b.getSupplier().getName(),
                    b.getBillDate(), b.getAmount()));
            if (b.getBillDate().isBefore(from)) {
                continue;
            }
            spend = spend.add(b.getAmount());
            if (b.getSupplier().isApproved()) {
                onRegister = onRegister.add(b.getAmount());
            }
            boolean purchase = b.getLines().stream().anyMatch(l -> l.getCategory().needsRequisition());
            if (purchase && requisitions.isRequired(b.getAmount())) {
                needing++;
                if (b.getRequisition() != null) {
                    cited++;
                }
            }
        }
        for (PurchaseOrder o : orderRepo.findByOrderDateBetweenAndStatusIn(from.minusDays(window), to, COMMITTED)) {
            BigDecimal rwf = o.getApprovalAmount();
            if (rwf == null) {
                try {
                    rwf = orderService.inRwf(o);
                } catch (BusinessException e) {
                    continue;                                // no usable rate for its currency today: left out
                }
            }
            rwf = rwf.setScale(0, RoundingMode.HALF_UP);     // an order in another currency, in whole RWF
            purchases.add(new SplitPurchases.Purchase(o.getNumber(), "/purchase-orders/" + o.getId(), o.getSupplier().getId(),
                    o.getSupplier().getName(), o.getOrderDate(), rwf));
            if (o.getOrderDate().isBefore(from)) {
                continue;
            }
            spend = spend.add(rwf);
            if (o.getSupplier().isApproved()) {
                onRegister = onRegister.add(rwf);
            }
            if (requisitions.isRequired(rwf)) {
                needing++;
                if (o.getRequisitionId() != null) {
                    cited++;
                }
            }
        }
        List<SplitPurchases.Flag> splits = SplitPurchases.detect(purchases, window, List.of(settings.getDecimal(SettingKey.EXPENSE_QUOTES_THREE_FROM),
                settings.getDecimal(SettingKey.EXPENSE_RFQ_FROM), settings.getDecimal(SettingKey.EXPENSE_TENDER_FROM))).stream()
                .filter(f -> !f.getTo().isBefore(from)).toList();
        long toRatify = billRepo.findByEmergencyTrueAndStatusAndRatifiedAtIsNullOrderByDecidedAtAsc(ExpenseBillStatus.APPROVED).size();
        long invoices = supplierInvoiceRepo.countByInvoiceDateBetween(from, to);
        return new Report(from, to, centreRows, locationRows, accountRows, operating, capital, byStatus, toRatify,
                new Indicators(needing, cited, spend, onRegister, manual, untagged, operating), splits,
                advances.retirementRates(from, to), invoices, pettyCash.funds());
    }

    /** Groups as rows, largest first; the key "" is the group no cost centre or location names. */
    private static List<Row> rows(Map<String, BigDecimal> sums, Function<String, String[]> label) {
        List<Row> rows = new ArrayList<>();
        sums.forEach((k, v) -> {
            if (v.signum() != 0) {
                String[] l = label.apply(k);
                rows.add(new Row(k, l[0], l[1], v));
            }
        });
        rows.sort(Comparator.comparing(Row::amount).reversed());
        return rows;
    }
}
