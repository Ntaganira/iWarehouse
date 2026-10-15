package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.entity.CreditNote;
import com.ntaganira.heritier.iWarehouse.entity.EbmReceipt;
import com.ntaganira.heritier.iWarehouse.entity.SalesInvoice;
import com.ntaganira.heritier.iWarehouse.enums.AccountKey;
import com.ntaganira.heritier.iWarehouse.enums.SalesInvoiceStatus;
import com.ntaganira.heritier.iWarehouse.repository.AccountRepository;
import com.ntaganira.heritier.iWarehouse.repository.CreditNoteRepository;
import com.ntaganira.heritier.iWarehouse.repository.EbmReceiptRepository;
import com.ntaganira.heritier.iWarehouse.repository.JournalLineRepository;
import com.ntaganira.heritier.iWarehouse.repository.SalesInvoiceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : EbmLedgerService.java
 * - Date      : 2026. 10. 15.
 * - User      : Hntaganira
 * - Desc      : EBM output against the sales ledger, monthly (BP Table 8, Table 9, Annex D; U.13): the invoices and
 *               credit notes of a month with their EBM receipts (signed, waiting, or none: issued before the EBM
 *               connection), their totals and what is signed, against what the ledger carries for the month (Sales
 *               less Sales Returns, plus VAT Output). The VAT report checks VAT per tax letter; this checks the fiscal
 *               receipts cover the documents and the documents the ledger.
 * </pre>
 */
@Service
@Transactional(readOnly = true)
public class EbmLedgerService {

    private final SalesInvoiceRepository invoiceRepo;
    private final CreditNoteRepository creditNoteRepo;
    private final EbmReceiptRepository receiptRepo;
    private final JournalLineRepository lineRepo;
    private final AccountRepository accountRepo;
    private final Clock clock;

    public EbmLedgerService(SalesInvoiceRepository invoiceRepo, CreditNoteRepository creditNoteRepo, EbmReceiptRepository receiptRepo,
                            JournalLineRepository lineRepo, AccountRepository accountRepo, Clock clock) {
        this.invoiceRepo = invoiceRepo;
        this.creditNoteRepo = creditNoteRepo;
        this.receiptRepo = receiptRepo;
        this.lineRepo = lineRepo;
        this.accountRepo = accountRepo;
        this.clock = clock;
    }

    /** A document of the month and its receipt (null: issued before the EBM connection). */
    public record Doc(boolean creditNote, UUID id, String number, LocalDate date, String customer, BigDecimal total, EbmReceipt receipt) {

        public boolean isSigned() {
            return receipt != null && receipt.isSigned();
        }

        public boolean isWaiting() {
            return receipt != null && !receipt.isSigned();
        }

        public String getLink() {
            return (creditNote ? "/credit-notes/" : "/invoices/") + id;
        }
    }

    public record Report(YearMonth month, List<Doc> invoices, List<Doc> creditNotes, BigDecimal sales, BigDecimal returns, BigDecimal vat) {

        public BigDecimal getInvoicesTotal() {
            return sum(invoices.stream().map(Doc::total));
        }

        public BigDecimal getInvoicesSigned() {
            return sum(invoices.stream().filter(Doc::isSigned).map(Doc::total));
        }

        public BigDecimal getCreditNotesTotal() {
            return sum(creditNotes.stream().map(Doc::total));
        }

        public BigDecimal getCreditNotesSigned() {
            return sum(creditNotes.stream().filter(Doc::isSigned).map(Doc::total));
        }

        /** Invoices less credit notes, VAT included. */
        public BigDecimal getDocumentsNet() {
            return getInvoicesTotal().subtract(getCreditNotesTotal());
        }

        /** What the signed receipts cover. */
        public BigDecimal getSignedNet() {
            return getInvoicesSigned().subtract(getCreditNotesSigned());
        }

        /** Without a receipt: documents issued before the EBM connection. */
        public BigDecimal getNoReceiptNet() {
            return sum(invoices.stream().filter(d -> d.receipt() == null).map(Doc::total))
                    .subtract(sum(creditNotes.stream().filter(d -> d.receipt() == null).map(Doc::total)));
        }

        /** What the ledger carries: Sales less Sales Returns, plus VAT Output. */
        public BigDecimal getLedgerNet() {
            return sales.subtract(returns).add(vat);
        }

        public BigDecimal getDocumentsLessLedger() {
            return getDocumentsNet().subtract(getLedgerNet());
        }

        /** Documents with a receipt still waiting for its signature. */
        public List<Doc> getWaiting() {
            return Stream.concat(invoices.stream(), creditNotes.stream()).filter(Doc::isWaiting).toList();
        }

        public long getNoReceipt() {
            return Stream.concat(invoices.stream(), creditNotes.stream()).filter(d -> d.receipt() == null).count();
        }

        private static BigDecimal sum(Stream<BigDecimal> values) {
            return values.reduce(BigDecimal.ZERO, BigDecimal::add);
        }
    }

    public Report report(YearMonth month) {
        LocalDate from = month.atDay(1);
        LocalDate to = month.atEndOfMonth();
        List<SalesInvoice> invoices = invoiceRepo.findByStatusAndInvoiceDateBetweenOrderByNumber(SalesInvoiceStatus.POSTED, from, to);
        List<CreditNote> notes = creditNoteRepo.findByCreditDateBetweenOrderByNumber(from, to);
        List<UUID> ids = new ArrayList<>();
        invoices.forEach(i -> ids.add(i.getId()));
        notes.forEach(n -> ids.add(n.getId()));
        Map<UUID, EbmReceipt> sale = new HashMap<>();
        Map<UUID, EbmReceipt> refund = new HashMap<>();
        if (!ids.isEmpty()) {
            for (EbmReceipt r : receiptRepo.findOfDocuments(ids)) {
                if (r.isRefund() && r.getCreditNoteId() != null) {
                    refund.put(r.getCreditNoteId(), r);
                } else if (!r.isRefund() && r.getInvoiceId() != null) {
                    sale.put(r.getInvoiceId(), r);
                }
            }
        }
        List<Doc> invoiceDocs = invoices.stream().map(i -> new Doc(false, i.getId(), i.getNumber(), i.getInvoiceDate(),
                i.getCustomer().getName(), i.getTotalAmount(), sale.get(i.getId()))).toList();
        List<Doc> noteDocs = notes.stream().map(n -> new Doc(true, n.getId(), n.getNumber(), n.getCreditDate(), n.getCustomer().getName(),
                n.getTotalAmount(), refund.get(n.getId()))).toList();
        return new Report(month, invoiceDocs, noteDocs, credits(AccountKey.SALES, from, to), credits(AccountKey.SALES_RETURNS, from, to).negate(),
                credits(AccountKey.VAT_OUTPUT, from, to));
    }

    public YearMonth lastMonth() {
        return YearMonth.now(clock).minusMonths(1);
    }

    /** Credits less debits of an account's lines dated in the period. */
    private BigDecimal credits(AccountKey key, LocalDate from, LocalDate to) {
        UUID account = accountRepo.findBySystemKey(key).orElseThrow(() -> new IllegalStateException("No account " + key)).getId();
        List<Object[]> rows = lineRepo.movement(account, from, to);
        if (rows.isEmpty()) {
            return BigDecimal.ZERO;
        }
        return ((BigDecimal) rows.get(0)[1]).subtract((BigDecimal) rows.get(0)[0]);
    }
}
