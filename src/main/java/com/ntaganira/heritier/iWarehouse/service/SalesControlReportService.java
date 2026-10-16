package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.entity.Customer;
import com.ntaganira.heritier.iWarehouse.entity.Territory;
import com.ntaganira.heritier.iWarehouse.entity.User;
import com.ntaganira.heritier.iWarehouse.enums.AccountKey;
import com.ntaganira.heritier.iWarehouse.enums.JournalSource;
import com.ntaganira.heritier.iWarehouse.enums.SaleLineKind;
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
 * - File      : SalesControlReportService.java
 * - Date      : 2026. 10. 16.
 * - User      : Hntaganira
 * - Desc      : Two monthly sales controls:
 *               - the discount analysis (SDP 15-16, R.06, M.15): the lines of issued invoices charged below the list
 *                 price they kept, what each would have charged at that price (the line's own pricing: glass by its
 *                 chargeable m² and pieces, processing by its quantity, VAT as the line says, rounded as the line was) and
 *                 what it did, by seller, customer, glass or processing, or invoice, against the period's sales
 *                 (DiscountAnalysis);
 *               - collection performance (BP 5.1, Annex D, Q.05): the receivable account's lines naming customers, each
 *                 charge with the invoice's salesperson and due day (the customer's terms and the invoice's extra days),
 *                 settled oldest first (CollectionAnalysis), by the customer's territory, the salesperson or the customer.
 *               Reads documents and journals only.
 * </pre>
 */
@Service
@Transactional(readOnly = true)
public class SalesControlReportService {

    private final SalesInvoiceLineRepository lineRepo;
    private final SalesInvoiceRepository invoiceRepo;
    private final JournalLineRepository journalLineRepo;
    private final AccountRepository accountRepo;
    private final CustomerRepository customerRepo;
    private final TerritoryRepository territoryRepo;
    private final UserRepository userRepo;
    private final LinePricing pricing;
    private final Clock clock;

    public SalesControlReportService(SalesInvoiceLineRepository lineRepo, SalesInvoiceRepository invoiceRepo, JournalLineRepository journalLineRepo,
                                     AccountRepository accountRepo, CustomerRepository customerRepo, TerritoryRepository territoryRepo,
                                     UserRepository userRepo, LinePricing pricing, Clock clock) {
        this.lineRepo = lineRepo;
        this.invoiceRepo = invoiceRepo;
        this.journalLineRepo = journalLineRepo;
        this.accountRepo = accountRepo;
        this.customerRepo = customerRepo;
        this.territoryRepo = territoryRepo;
        this.userRepo = userRepo;
        this.pricing = pricing;
        this.clock = clock;
    }

    public LocalDate today() {
        return LocalDate.now(clock);
    }

    // ---------------------------------------------------------------- discounts (R.06, M.15)

    public record Discounts(LocalDate from, LocalDate to, DiscountAnalysis.GroupBy by, List<DiscountAnalysis.Row> rows, DiscountAnalysis.Row total,
                            BigDecimal sales, List<DiscountAnalysis.Line> lines) {

        /** The discounts as a % of what the period's invoices charged, one decimal. */
        public BigDecimal getSharePercent() {
            return sales.signum() <= 0 ? BigDecimal.ZERO.setScale(1)
                    : total.getDiscount().multiply(BigDecimal.valueOf(100)).divide(sales, 1, RoundingMode.HALF_UP);
        }
    }

    public Discounts discounts(LocalDate from, LocalDate to, DiscountAnalysis.GroupBy by) {
        Map<String, String> names = names();
        List<DiscountAnalysis.Line> lines = new ArrayList<>();
        for (Object[] r : lineRepo.pricedOffListIn(from, to)) {
            SaleLineKind kind = (SaleLineKind) r[6];
            BigDecimal listPrice = (BigDecimal) r[9];
            boolean vatIncluded = (Boolean) r[13];
            BigDecimal rate = (BigDecimal) r[14];
            BigDecimal amount = (BigDecimal) r[15];
            BigDecimal listAmount = kind == SaleLineKind.SERVICE
                    ? pricing.amount(listPrice, (BigDecimal) r[12], 1, vatIncluded, rate)
                    : pricing.amount(listPrice, (BigDecimal) r[10], ((Number) r[11]).intValue(), vatIncluded, rate);
            String seller = (String) r[3];
            String item = kind == SaleLineKind.SERVICE && r[8] != null ? (String) r[8] : (String) r[7];
            lines.add(new DiscountAnalysis.Line((UUID) r[0], (String) r[1], (LocalDate) r[2], seller, seller == null ? null : names.getOrDefault(seller, seller),
                    (UUID) r[4], (String) r[5], item, (String) r[16], listAmount, amount));
        }
        List<DiscountAnalysis.Line> discounted = new ArrayList<>(DiscountAnalysis.discounted(lines));
        discounted.sort(Comparator.comparing(DiscountAnalysis.Line::getDiscount).reversed().thenComparing(DiscountAnalysis.Line::invoiceNumber));
        return new Discounts(from, to, by, DiscountAnalysis.group(lines, by), DiscountAnalysis.total(lines), invoiceRepo.issuedTotal(from, to), discounted);
    }

    // ---------------------------------------------------------------- collections (Q.05)

    /** A group of the collections report: its label (territory, salesperson or customer) and figures. */
    public record CollectionRow(String key, String label, UUID customerId, CollectionAnalysis.Row figures) {
    }

    public record Collections(LocalDate from, LocalDate to, CollectionAnalysis.GroupBy by, List<CollectionRow> rows, CollectionAnalysis.Row total) {
    }

    public Collections collections(LocalDate from, LocalDate to, CollectionAnalysis.GroupBy by) {
        UUID receivable = accountRepo.findBySystemKey(AccountKey.RECEIVABLE).orElseThrow(() -> new IllegalStateException("No receivable account"))
                .getId();
        List<Object[]> rows = journalLineRepo.customerLinesUntil(receivable, to);
        Set<UUID> customerIds = new HashSet<>();
        Set<UUID> invoiceIds = new HashSet<>();
        for (Object[] r : rows) {
            customerIds.add((UUID) r[0]);
            if (ReceivableMovement.NEW_CREDIT.contains((JournalSource) r[2]) && r[3] != null) {
                invoiceIds.add((UUID) r[3]);
            }
        }
        Map<UUID, Customer> customers = customerRepo.findAllById(customerIds).stream().collect(Collectors.toMap(Customer::getId, Function.identity()));
        Map<UUID, Object[]> sellers = new HashMap<>();
        if (!invoiceIds.isEmpty()) {
            for (Object[] s : invoiceRepo.sellersOf(invoiceIds)) {
                sellers.put((UUID) s[0], s);
            }
        }
        List<CollectionAnalysis.Charge> charges = new ArrayList<>();
        List<CollectionAnalysis.Credit> credits = new ArrayList<>();
        for (Object[] r : rows) {
            UUID customerId = (UUID) r[0];
            LocalDate date = (LocalDate) r[1];
            JournalSource source = (JournalSource) r[2];
            BigDecimal debit = (BigDecimal) r[4];
            BigDecimal credit = (BigDecimal) r[5];
            if (debit != null && debit.signum() > 0) {
                Object[] s = r[3] == null ? null : sellers.get((UUID) r[3]);
                Customer c = customers.get(customerId);
                int terms = (c == null ? 0 : c.getPaymentTermsDays()) + (s == null || s[2] == null ? 0 : ((Number) s[2]).intValue());
                charges.add(new CollectionAnalysis.Charge(customerId, date, date.plusDays(terms), s == null ? null : (String) s[1], debit));
            }
            if (credit != null && credit.signum() > 0) {
                credits.add(new CollectionAnalysis.Credit(customerId, date, ReceivableMovement.COLLECTIONS.contains(source), credit));
            }
        }
        CollectionAnalysis.Result result = CollectionAnalysis.allocate(charges, credits, from, to);
        Function<UUID, String> territoryOf = id -> {
            Customer c = customers.get(id);
            return c == null || c.getTerritoryId() == null ? "" : c.getTerritoryId().toString();
        };
        List<CollectionAnalysis.Row> grouped = switch (by) {
            case TERRITORY -> CollectionAnalysis.group(result, p -> territoryOf.apply(p.charge().customerId()), territoryOf);
            case SALESPERSON -> CollectionAnalysis.group(result, p -> p.charge().salesperson(), id -> "");
            case CUSTOMER -> CollectionAnalysis.group(result, p -> p.charge().customerId().toString(), UUID::toString);
        };
        Map<String, String> labels = new HashMap<>();
        switch (by) {
            case TERRITORY -> {
                for (Territory t : territoryRepo.findAllByOrderByCodeAsc()) {
                    labels.put(t.getId().toString(), t.getCode() + " · " + t.getName());
                }
            }
            case SALESPERSON -> labels.putAll(names());
            case CUSTOMER -> customers.values().forEach(c -> labels.put(c.getId().toString(), c.getName()));
        }
        List<CollectionRow> list = new ArrayList<>();
        for (CollectionAnalysis.Row row : grouped) {
            UUID customerId = by == CollectionAnalysis.GroupBy.CUSTOMER && !row.key().isEmpty() ? UUID.fromString(row.key()) : null;
            list.add(new CollectionRow(row.key(), row.key().isEmpty() ? null : labels.getOrDefault(row.key(), row.key()), customerId, row));
        }
        return new Collections(from, to, by, list, CollectionAnalysis.total(grouped));
    }

    private Map<String, String> names() {
        return userRepo.findAll().stream()
                .collect(Collectors.toMap(User::getUsername, u -> u.getFullName() == null ? u.getUsername() : u.getFullName(), (a, b) -> a));
    }
}
