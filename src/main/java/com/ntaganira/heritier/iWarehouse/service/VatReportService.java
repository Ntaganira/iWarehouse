package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.entity.Account;
import com.ntaganira.heritier.iWarehouse.entity.JournalLine;
import com.ntaganira.heritier.iWarehouse.enums.AccountKey;
import com.ntaganira.heritier.iWarehouse.repository.AccountRepository;
import com.ntaganira.heritier.iWarehouse.repository.CreditNoteLineRepository;
import com.ntaganira.heritier.iWarehouse.repository.JournalEntryRepository;
import com.ntaganira.heritier.iWarehouse.repository.JournalLineRepository;
import com.ntaganira.heritier.iWarehouse.repository.SalesInvoiceLineRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : VatReportService.java
 * - Date      : 2026. 10. 09.
 * - User      : Hntaganira
 * - Desc      : The monthly VAT report for filing (TAX-05). Output VAT per tax letter from the invoices issued and the
 *               credit notes dated in the month (VatReport); input VAT from the VAT Input account's lines of the month
 *               (VAT recovered on purchases, booked by manual journal until purchases carry VAT); VAT payable is
 *               output less input. The VAT Output account's movement of the month is shown next to the output VAT:
 *               the two agree when every sale and credit note posted its VAT.
 * </pre>
 */
@Service
@Transactional(readOnly = true)
public class VatReportService {

    private final SalesInvoiceLineRepository salesLineRepo;
    private final CreditNoteLineRepository creditLineRepo;
    private final JournalLineRepository journalLineRepo;
    private final JournalEntryRepository entryRepo;
    private final AccountRepository accountRepo;
    private final Clock clock;

    public VatReportService(SalesInvoiceLineRepository salesLineRepo, CreditNoteLineRepository creditLineRepo,
                            JournalLineRepository journalLineRepo, JournalEntryRepository entryRepo, AccountRepository accountRepo, Clock clock) {
        this.salesLineRepo = salesLineRepo;
        this.creditLineRepo = creditLineRepo;
        this.journalLineRepo = journalLineRepo;
        this.entryRepo = entryRepo;
        this.accountRepo = accountRepo;
        this.clock = clock;
    }

    /** A month's VAT: output per letter, input from the ledger, and the VAT Output account's movement to check against. */
    public record Report(YearMonth month, VatReport.Result output, BigDecimal inputVat, List<JournalLine> inputLines,
                         BigDecimal ledgerOutputVat) {

        public LocalDate getFrom() {
            return month.atDay(1);
        }

        public LocalDate getTo() {
            return month.atEndOfMonth();
        }

        public BigDecimal getOutputVat() {
            return output.getVat();
        }

        /** Output less input: positive to pay, negative a credit. */
        public BigDecimal getPayable() {
            return getOutputVat().subtract(inputVat);
        }

        /** The VAT Output account's movement less the output VAT of the documents: 0 when they agree. */
        public BigDecimal getLedgerDifference() {
            return ledgerOutputVat.subtract(getOutputVat());
        }
    }

    /** The months to choose from, newest first: from the ledger's first month (this month when it has none) to this month. */
    public List<YearMonth> months() {
        YearMonth now = YearMonth.from(today());
        LocalDate first = entryRepo.firstEntryDate();
        YearMonth start = first == null ? now : YearMonth.from(first);
        List<YearMonth> months = new ArrayList<>();
        for (YearMonth m = now; !m.isBefore(start); m = m.minusMonths(1)) {
            months.add(m);
        }
        return months;
    }

    public Report report(YearMonth month) {
        LocalDate from = month.atDay(1);
        LocalDate to = month.atEndOfMonth();
        VatReport.Result output = VatReport.of(docLines(salesLineRepo.vatLines(from, to)), docLines(creditLineRepo.vatLines(from, to)));
        Account input = account(AccountKey.VAT_INPUT);
        List<JournalLine> inputLines = journalLineRepo.findAccountLines(input.getId(), from, to);
        BigDecimal inputVat = inputLines.stream().map(l -> l.getDebit().subtract(l.getCredit())).reduce(BigDecimal.ZERO, BigDecimal::add);
        Account outputAccount = account(AccountKey.VAT_OUTPUT);
        BigDecimal ledgerOutput = BigDecimal.ZERO;
        for (Object[] r : journalLineRepo.movements(from, to)) {
            if (outputAccount.getId().equals(r[0])) {
                ledgerOutput = ((BigDecimal) r[2]).subtract((BigDecimal) r[1]);
            }
        }
        return new Report(month, output, inputVat, inputLines, ledgerOutput);
    }

    public LocalDate today() {
        return LocalDate.now(clock);
    }

    private Account account(AccountKey key) {
        return accountRepo.findBySystemKey(key).orElseThrow(() -> new IllegalStateException("No account " + key));
    }

    private static List<VatReport.DocLine> docLines(List<Object[]> rows) {
        return rows.stream().map(r -> new VatReport.DocLine((UUID) r[0], (String) r[1], (BigDecimal) r[2], (BigDecimal) r[3])).toList();
    }
}
