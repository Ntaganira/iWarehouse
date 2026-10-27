package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.entity.Customer;
import com.ntaganira.heritier.iWarehouse.entity.JournalLine;
import com.ntaganira.heritier.iWarehouse.repository.SalesInvoiceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : CustomerAgeing.java
 * - Date      : 2026. 10. 15.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : What customers owe, by age (ACC-09): their lines on the receivable account settled oldest first (Ageing),
 *               each charge due its date plus the customer's terms, plus the days an invoice was approved with extended
 *               terms (F-03). The counter asks it whether a customer has invoices overdue (SDP 11: overdue invoices block
 *               credit); the customer accounts read their statement's ageing and the receivables report from it.
 * </pre>
 */
@Service
@Transactional(readOnly = true)
public class CustomerAgeing {

    private final JournalService journalService;
    private final SalesInvoiceRepository invoiceRepo;
    private final Clock clock;

    public CustomerAgeing(JournalService journalService, SalesInvoiceRepository invoiceRepo, Clock clock) {
        this.journalService = journalService;
        this.invoiceRepo = invoiceRepo;
        this.clock = clock;
    }

    /** Owed past its due date (beyond the grace days) and how many days the oldest of it is late. */
    public record Overdue(BigDecimal amount, long oldestDays) {

        public static final Overdue NONE = new Overdue(BigDecimal.ZERO, 0);

        public boolean any() {
            return amount.signum() > 0;
        }
    }

    /** A customer's ageing from their lines (as their statement reads them). */
    public Ageing.Result of(Customer customer, List<JournalLine> lines) {
        Map<UUID, Integer> extra = extendedTerms();
        return Ageing.of(lines.stream().map(l -> new Ageing.Entry(l.getEntry().getEntryDate(), l.getDebit(), l.getCredit(),
                extra.getOrDefault(l.getEntry().getSourceId(), 0))).toList(), customer.getPaymentTermsDays(), today());
    }

    public Ageing.Result of(Customer customer) {
        return of(customer, journalService.customerLines(customer.getId()));
    }

    /** Every customer's entries, by customer: the receivables report. */
    public Map<UUID, List<Ageing.Entry>> entries() {
        Map<UUID, Integer> extra = extendedTerms();
        Map<UUID, List<Ageing.Entry>> byCustomer = new HashMap<>();
        for (Object[] row : journalService.receivableEntries()) {
            int days = row.length > 4 && row[4] != null ? extra.getOrDefault((UUID) row[4], 0) : 0;
            byCustomer.computeIfAbsent((UUID) row[0], k -> new ArrayList<>())
                    .add(new Ageing.Entry((LocalDate) row[1], (BigDecimal) row[2], (BigDecimal) row[3], days));
        }
        return byCustomer;
    }

    /** What the customer owes more than {@code graceDays} past due; none when nothing is that late. */
    public Overdue overdue(Customer customer, int graceDays) {
        Ageing.Result result = of(customer);
        if (result.oldestDue() == null) {
            return Overdue.NONE;
        }
        long late = ChronoUnit.DAYS.between(result.oldestDue(), today());
        if (late <= graceDays || result.getOverdue().signum() <= 0) {
            return Overdue.NONE;
        }
        return new Overdue(result.getOverdue(), late);
    }

    private Map<UUID, Integer> extendedTerms() {
        Map<UUID, Integer> extra = new HashMap<>();
        for (Object[] row : invoiceRepo.extendedTerms()) {
            extra.put((UUID) row[0], ((Number) row[1]).intValue());
        }
        return extra;
    }

    public LocalDate today() {
        return LocalDate.now(clock);
    }
}
