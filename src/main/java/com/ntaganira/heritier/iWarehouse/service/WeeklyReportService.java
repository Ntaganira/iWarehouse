package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.entity.CreditNote;
import com.ntaganira.heritier.iWarehouse.entity.StockVariance;
import com.ntaganira.heritier.iWarehouse.repository.CreditNoteRepository;
import com.ntaganira.heritier.iWarehouse.repository.SalesInvoiceRepository;
import com.ntaganira.heritier.iWarehouse.repository.StockVarianceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : WeeklyReportService.java
 * - Date      : 2026. 10. 16.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : The weekly report (SDP 15, 16; M.14): a week's sales against target (the business, by territory and market),
 *               the count variances raised and what they were worth, the sales given up before they were issued (voids)
 *               and the credit notes, and the receivables by age today. Reads documents and journals only.
 * </pre>
 */
@Service
@Transactional(readOnly = true)
public class WeeklyReportService {

    public record Report(LocalDate from, LocalDate to, SalesTargetService.Report sales, long variances, BigDecimal missingValue,
                         BigDecimal foundValue, long voids, BigDecimal voidAmount, long creditNotes, BigDecimal creditAmount,
                         Map<Ageing.Bucket, BigDecimal> receivables, BigDecimal receivablesTotal) {
    }

    private final SalesTargetService targets;
    private final StockVarianceRepository varianceRepo;
    private final SalesInvoiceRepository invoiceRepo;
    private final CreditNoteRepository creditNoteRepo;
    private final CustomerAccountService customerAccounts;

    public WeeklyReportService(SalesTargetService targets, StockVarianceRepository varianceRepo, SalesInvoiceRepository invoiceRepo,
                               CreditNoteRepository creditNoteRepo, CustomerAccountService customerAccounts) {
        this.targets = targets;
        this.varianceRepo = varianceRepo;
        this.invoiceRepo = invoiceRepo;
        this.creditNoteRepo = creditNoteRepo;
        this.customerAccounts = customerAccounts;
    }

    public LocalDate today() {
        return targets.today();
    }

    /** The week (Monday to Sunday) a day falls in. */
    public Report report(LocalDate day) {
        LocalDate[] week = targets.week(day);
        LocalDate from = week[0], to = week[1];
        SalesTargetService.Report sales = targets.report(from, to, true);
        List<StockVariance> variances = varianceRepo.findByCreatedAtBetweenOrderByCreatedAtAsc(from.atStartOfDay(), to.atTime(LocalTime.MAX));
        BigDecimal missing = variances.stream().map(v -> v.getMissingValue() == null ? BigDecimal.ZERO : v.getMissingValue()).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal found = variances.stream().map(v -> v.getFoundValue() == null ? BigDecimal.ZERO : v.getFoundValue()).reduce(BigDecimal.ZERO, BigDecimal::add);
        Object[] voided = invoiceRepo.voidedIn(from.atStartOfDay(), to.atTime(LocalTime.MAX)).get(0);
        List<CreditNote> notes = creditNoteRepo.findByCreditDateBetweenOrderByNumber(from, to);
        BigDecimal credited = notes.stream().map(n -> n.getTotalAmount() == null ? BigDecimal.ZERO : n.getTotalAmount()).reduce(BigDecimal.ZERO, BigDecimal::add);
        CustomerAccountService.Receivables receivables = customerAccounts.receivables();
        return new Report(from, to, sales, variances.size(), missing, found, ((Number) voided[0]).longValue(), (BigDecimal) voided[1], notes.size(),
                credited, receivables.totals(), receivables.total());
    }
}
