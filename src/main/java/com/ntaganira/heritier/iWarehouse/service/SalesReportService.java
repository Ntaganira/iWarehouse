package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.entity.User;
import com.ntaganira.heritier.iWarehouse.enums.AccountKey;
import com.ntaganira.heritier.iWarehouse.enums.CreditNoteKind;
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
import java.util.stream.Collectors;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : SalesReportService.java
 * - Date      : 2026. 10. 10.
 * - User      : Hntaganira
 * - Desc      : The sales reports (RPT-05): the invoices issued in a period, less their credit notes, grouped by customer,
 *               glass or processing, salesperson (who took the payment), day, month or invoice, with the gross margin
 *               at MAC from the Cost of Goods Sold the sale, hand-over and credit note journals posted. Reads projections
 *               of the period's documents; the rules are SalesAnalysis's.
 * </pre>
 */
@Service
@Transactional(readOnly = true)
public class SalesReportService {

    private static final BigDecimal MM2_PER_M2 = BigDecimal.valueOf(1_000_000);

    private final SalesInvoiceRepository invoiceRepo;
    private final SalesInvoiceLineRepository lineRepo;
    private final CreditNoteRepository creditNoteRepo;
    private final CreditNoteLineRepository creditLineRepo;
    private final SalesDeliveryRepository deliveryRepo;
    private final JournalLineRepository journalLineRepo;
    private final UserRepository userRepo;
    private final Clock clock;

    public SalesReportService(SalesInvoiceRepository invoiceRepo, SalesInvoiceLineRepository lineRepo, CreditNoteRepository creditNoteRepo,
                              CreditNoteLineRepository creditLineRepo, SalesDeliveryRepository deliveryRepo,
                              JournalLineRepository journalLineRepo, UserRepository userRepo, Clock clock) {
        this.invoiceRepo = invoiceRepo;
        this.lineRepo = lineRepo;
        this.creditNoteRepo = creditNoteRepo;
        this.creditLineRepo = creditLineRepo;
        this.deliveryRepo = deliveryRepo;
        this.journalLineRepo = journalLineRepo;
        this.userRepo = userRepo;
        this.clock = clock;
    }

    /** A choice for a filter: its value and label. */
    public record Option(String value, String label) {
    }

    /**
     * The report: its rows and total, how many of its invoices have pieces still to hand over, and the customers, items and
     * salespeople of the period for the filters.
     */
    public record Report(LocalDate from, LocalDate to, SalesAnalysis.GroupBy groupBy, List<SalesAnalysis.Row> rows,
                         SalesAnalysis.Row total, long pending, List<Option> customers, List<Option> items, List<Option> salespeople) {
    }

    public Report report(LocalDate from, LocalDate to, SalesAnalysis.GroupBy by, UUID customer, String item, String salesperson) {
        List<SalesAnalysis.Fact> all = facts(from, to);
        List<SalesAnalysis.Fact> facts = all.stream()
                .filter(f -> customer == null || customer.equals(f.invoice().customerId()))
                .filter(f -> item == null || item.equals(f.itemKey()))
                .filter(f -> salesperson == null || salesperson.equals(f.invoice().salesperson()))
                .toList();
        return new Report(from, to, by, SalesAnalysis.group(facts, by), SalesAnalysis.total(facts), SalesAnalysis.pending(facts),
                options(all, f -> String.valueOf(f.invoice().customerId()), f -> f.invoice().customer()),
                options(all, SalesAnalysis.Fact::itemKey, SalesAnalysis.Fact::itemLabel),
                options(all, f -> f.invoice().salesperson(), f -> f.invoice().salespersonName()));
    }

    /** Today's net sales, cost and margin (the owner dashboard, RPT-01). */
    public SalesAnalysis.Row day(LocalDate day) {
        return SalesAnalysis.total(facts(day, day));
    }

    /** Net sales per day over a period, every day included (the dashboard chart). */
    public Map<LocalDate, BigDecimal> netPerDay(LocalDate from, LocalDate to) {
        Map<LocalDate, BigDecimal> days = new LinkedHashMap<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            days.put(d, BigDecimal.ZERO);
        }
        for (SalesAnalysis.Row r : SalesAnalysis.group(facts(from, to), SalesAnalysis.GroupBy.DAY)) {
            days.put(r.date(), r.net());
        }
        return days;
    }

    public LocalDate today() {
        return LocalDate.now(clock);
    }

    // ---------------------------------------------------------------- the period's documents

    private List<SalesAnalysis.Fact> facts(LocalDate from, LocalDate to) {
        Map<String, String> names = userRepo.findAll().stream()
                .collect(Collectors.toMap(User::getUsername, u -> u.getFullName() == null ? u.getUsername() : u.getFullName(), (a, b) -> a));

        // Lines first: the pieces each invoice has to hand over
        List<SalesAnalysis.Line> lines = new ArrayList<>();
        Map<UUID, Object[]> lineRows = new HashMap<>();
        Map<UUID, Long> customPieces = new HashMap<>();
        for (Object[] r : lineRepo.issuedIn(from, to)) {
            UUID invoiceId = (UUID) r[0];
            UUID lineId = (UUID) r[1];
            SaleLineKind kind = (SaleLineKind) r[2];
            int pieces = (Integer) r[7];
            boolean glass = kind != SaleLineKind.SERVICE;
            String key = glass ? SalesAnalysis.glassKey((UUID) r[3]) : SalesAnalysis.serviceKey((UUID) r[5]);
            String label = glass ? (String) r[4] : (String) r[6];
            lines.add(new SalesAnalysis.Line(invoiceId, lineId, key, label, (UUID) r[3], glass, glass ? area((Integer) r[8], (Integer) r[9], pieces)
                    : BigDecimal.ZERO, (BigDecimal) r[10]));
            lineRows.put(lineId, r);
            if (kind == SaleLineKind.CUSTOM_PIECE) {
                customPieces.merge(invoiceId, (long) pieces, Long::sum);
            }
        }

        List<SalesAnalysis.CreditNote> creditNotes = new ArrayList<>();
        Map<UUID, UUID> invoiceOfCredit = new HashMap<>();
        for (Object[] r : creditNoteRepo.ofInvoicesIssuedIn(from, to)) {
            creditNotes.add(new SalesAnalysis.CreditNote((UUID) r[0], (UUID) r[1], (BigDecimal) r[2]));
            invoiceOfCredit.put((UUID) r[0], (UUID) r[1]);
        }
        List<SalesAnalysis.Credit> credits = new ArrayList<>();
        Map<UUID, Long> cancelledPieces = new HashMap<>();
        for (Object[] r : creditLineRepo.ofInvoicesIssuedIn(from, to)) {
            Object[] line = lineRows.get((UUID) r[1]);
            if (line == null) {
                continue;
            }
            int pieces = (Integer) r[2];
            boolean glass = line[2] != SaleLineKind.SERVICE;
            credits.add(new SalesAnalysis.Credit((UUID) r[0], (UUID) r[1], glass ? area((Integer) line[8], (Integer) line[9], pieces)
                    : BigDecimal.ZERO, (BigDecimal) r[3]));
            if (r[4] == CreditNoteKind.CANCEL && line[2] == SaleLineKind.CUSTOM_PIECE) {
                cancelledPieces.merge((UUID) line[0], (long) pieces, Long::sum);
            }
        }
        Map<UUID, Long> handedOver = new HashMap<>();
        for (Object[] r : deliveryRepo.handedOverPerInvoice(from, to)) {
            handedOver.put((UUID) r[0], ((Number) r[1]).longValue());
        }

        List<SalesAnalysis.Invoice> invoices = new ArrayList<>();
        for (Object[] r : invoiceRepo.issuedIn(from, to)) {
            UUID id = (UUID) r[0];
            long toHandOver = customPieces.getOrDefault(id, 0L) - handedOver.getOrDefault(id, 0L) - cancelledPieces.getOrDefault(id, 0L);
            String postedBy = (String) r[6];
            invoices.add(new SalesAnalysis.Invoice(id, (String) r[1], (LocalDate) r[2], (UUID) r[3], (String) r[4], (String) r[5], postedBy,
                    postedBy == null ? "" : names.getOrDefault(postedBy, postedBy), (BigDecimal) r[7], toHandOver > 0));
        }

        // Cost of Goods Sold of each journal of an invoice (sale, hand-over, credit note), with its inventory lines per glass
        Map<UUID, String> productCodes = new HashMap<>();
        lines.stream().filter(SalesAnalysis.Line::glass).forEach(l -> productCodes.putIfAbsent(l.productId(), l.itemLabel()));
        Map<String, UUID> invoiceOfDoc = new HashMap<>();
        Map<String, BigDecimal> cogsOfDoc = new LinkedHashMap<>();
        Map<String, Map<UUID, BigDecimal>> stockOfDoc = new HashMap<>();
        for (Object[] r : journalLineRepo.costOfSales(EnumSet.of(JournalSource.SALES_INVOICE, JournalSource.SALES_DELIVERY,
                JournalSource.CREDIT_NOTE), from)) {
            UUID invoiceId = r[0] == JournalSource.CREDIT_NOTE ? invoiceOfCredit.get((UUID) r[1]) : (UUID) r[1];
            if (invoiceId == null) {
                continue;
            }
            String doc = r[0] + ":" + r[1];
            invoiceOfDoc.put(doc, invoiceId);
            if (r[2] == AccountKey.COGS) {
                cogsOfDoc.merge(doc, (BigDecimal) r[4], BigDecimal::add);
            } else if (r[3] != null) {
                stockOfDoc.computeIfAbsent(doc, k -> new HashMap<>()).merge((UUID) r[3], (BigDecimal) r[4], BigDecimal::add);
            }
        }
        List<SalesAnalysis.Cost> costs = new ArrayList<>();
        cogsOfDoc.forEach((doc, amount) -> costs.add(new SalesAnalysis.Cost(invoiceOfDoc.get(doc), amount, stockOfDoc.getOrDefault(doc, Map.of()))));
        return SalesAnalysis.facts(invoices, lines, creditNotes, credits, costs, productCodes);
    }

    /** The glass's own m²: width x height x pieces. */
    private static BigDecimal area(int widthMm, int heightMm, int pieces) {
        return BigDecimal.valueOf((long) widthMm * heightMm * pieces).divide(MM2_PER_M2, 4, RoundingMode.HALF_UP);
    }

    private static List<Option> options(List<SalesAnalysis.Fact> facts, java.util.function.Function<SalesAnalysis.Fact, String> value,
                                        java.util.function.Function<SalesAnalysis.Fact, String> label) {
        Map<String, String> seen = new LinkedHashMap<>();
        facts.forEach(f -> seen.putIfAbsent(value.apply(f), label.apply(f)));
        return seen.entrySet().stream().map(e -> new Option(e.getKey(), e.getValue() == null ? "" : e.getValue()))
                .sorted(Comparator.comparing(Option::label, String.CASE_INSENSITIVE_ORDER)).toList();
    }
}
