package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.entity.*;
import com.ntaganira.heritier.iWarehouse.entity.Currency;
import com.ntaganira.heritier.iWarehouse.enums.DocumentType;
import com.ntaganira.heritier.iWarehouse.enums.GoodsReceiptStatus;
import com.ntaganira.heritier.iWarehouse.enums.JournalSource;
import com.ntaganira.heritier.iWarehouse.enums.PaymentMethod;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.exception.NotFoundException;
import com.ntaganira.heritier.iWarehouse.repository.*;
import com.ntaganira.heritier.iWarehouse.security.AppUserPrincipal;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : SupplierAccountService.java
 * - Date      : 2026. 10. 09.
 * - User      : Hntaganira
 * - Desc      : Supplier accounts (ACC-08, ACC-09). What is owed to a supplier is the payable account's lines that name
 *               them: supplier invoices and shipment bills owed, payments and credit notes settling them; per currency
 *               (Payables: the oldest items settled first, each keeping the RWF it was booked at), with the RWF ageing
 *               at the supplier's terms. A supplier invoice (SINV) bills posted goods receipts not invoiced yet, each at
 *               the value it left on Goods Received Not Invoiced, once; its total must match the supplier's. A payment
 *               (SPAY) pays at most what is owed in a currency at that day's rate; the RWF the settled items were booked
 *               at less the RWF paid is the realised FX gain or loss. Both lock the supplier first.
 * </pre>
 */
@Service
@Transactional(readOnly = true)
public class SupplierAccountService {

    private final SupplierInvoiceRepository invoiceRepo;
    private final SupplierInvoiceLineRepository invoiceLineRepo;
    private final SupplierPaymentRepository paymentRepo;
    private final SupplierRepository supplierRepo;
    private final GoodsReceiptRepository receiptRepo;
    private final CurrencyRepository currencyRepo;
    private final JournalService journalService;
    private final ExchangeRateService rates;
    private final PostingService postingService;
    private final DocumentNumberService numbers;
    private final PeriodLock periods;
    private final Clock clock;

    public SupplierAccountService(SupplierInvoiceRepository invoiceRepo, SupplierInvoiceLineRepository invoiceLineRepo,
                                  SupplierPaymentRepository paymentRepo, SupplierRepository supplierRepo, GoodsReceiptRepository receiptRepo,
                                  CurrencyRepository currencyRepo, JournalService journalService, ExchangeRateService rates,
                                  PostingService postingService, DocumentNumberService numbers, PeriodLock periods, Clock clock) {
        this.invoiceRepo = invoiceRepo;
        this.invoiceLineRepo = invoiceLineRepo;
        this.paymentRepo = paymentRepo;
        this.supplierRepo = supplierRepo;
        this.receiptRepo = receiptRepo;
        this.currencyRepo = currencyRepo;
        this.journalService = journalService;
        this.rates = rates;
        this.postingService = postingService;
        this.numbers = numbers;
        this.periods = periods;
        this.clock = clock;
    }

    /** A line of a supplier's statement and what is owed to them after it (RWF as booked). */
    public record StatementRow(JournalLine line, BigDecimal balance) {

        /** The line's amount in its currency, when it is not RWF. */
        public boolean isForeign() {
            return line.getCurrencyCode() != null;
        }
    }

    /** A goods receipt not invoiced yet and what it left on GRNI: its currency, amount in it, rate and RWF. */
    public record Uninvoiced(GoodsReceipt receipt, String currency, BigDecimal amount, BigDecimal rate, BigDecimal base) {
    }

    /** A supplier's account: the statement (newest first), what is owed per currency, the RWF owed, the ageing, receipts to invoice. */
    public record Account(List<StatementRow> statement, Collection<Payables.Open> open, BigDecimal balance, Ageing.Result ageing,
                          List<Uninvoiced> uninvoiced) {
    }

    /** A supplier of the payables report: what is owed per currency and the RWF ageing. */
    public record Aged(Supplier supplier, Collection<Payables.Open> open, Ageing.Result ageing) {
    }

    /** The payables report: each supplier owed, the largest first, and the totals per bucket (RWF as booked). */
    public record PayablesReport(List<Aged> suppliers, Map<Ageing.Bucket, BigDecimal> totals, BigDecimal total) {
    }

    /** A supplier's invoice: their reference, date, total (to check against the receipts), the receipts it bills, notes. */
    public record InvoiceForm(String supplierRef, LocalDate invoiceDate, BigDecimal amount, List<UUID> receiptIds, String notes) {
    }

    /** A payment: the currency and amount paid, how, its reference, notes. */
    public record PaymentForm(String currencyCode, BigDecimal amount, PaymentMethod method, String reference, String notes) {
    }

    // ---------------------------------------------------------------- reading

    public Account account(Supplier supplier) {
        List<JournalLine> lines = journalService.supplierLines(supplier.getId());
        List<StatementRow> rows = new ArrayList<>();
        BigDecimal balance = BigDecimal.ZERO;
        for (JournalLine l : lines) {
            balance = balance.add(l.getCredit()).subtract(l.getDebit());
            rows.add(new StatementRow(l, balance));
        }
        Collections.reverse(rows);
        return new Account(rows, Payables.open(payableLines(lines)).values(), balance, ageing(lines, supplier), uninvoiced(supplier));
    }

    /** What is owed in each currency (the payment form offers these). */
    public Map<String, Payables.Open> open(Supplier supplier) {
        return Payables.open(payableLines(journalService.supplierLines(supplier.getId())));
    }

    /** The supplier's posted goods receipts not invoiced yet, oldest first, with what each left on GRNI. */
    public List<Uninvoiced> uninvoiced(Supplier supplier) {
        List<GoodsReceipt> posted = receiptRepo.findByPurchaseOrder_Supplier_IdAndStatusOrderByReceivedDateAscNumberAsc(supplier.getId(),
                GoodsReceiptStatus.POSTED);
        if (posted.isEmpty()) {
            return List.of();
        }
        Set<UUID> billed = invoiceLineRepo.findByGoodsReceiptIdIn(posted.stream().map(GoodsReceipt::getId).toList()).stream()
                .map(SupplierInvoiceLine::getGoodsReceiptId).collect(Collectors.toSet());
        List<GoodsReceipt> open = posted.stream().filter(r -> !billed.contains(r.getId())).toList();
        Map<UUID, JournalLine> grni = new HashMap<>();
        journalService.grniOfReceipts(open.stream().map(GoodsReceipt::getId).toList())
                .forEach(l -> grni.put(l.getEntry().getSourceId(), l));
        String base = baseCurrency();
        List<Uninvoiced> result = new ArrayList<>();
        for (GoodsReceipt r : open) {
            JournalLine l = grni.get(r.getId());
            if (l == null || l.getCredit().signum() <= 0) {
                continue;                                     // nothing left to invoice (a receipt of broken sheets only)
            }
            boolean foreign = l.getCurrencyCode() != null;
            result.add(new Uninvoiced(r, foreign ? l.getCurrencyCode() : base, foreign ? l.getFxAmount().abs() : l.getCredit(),
                    foreign ? l.getRate() : null, l.getCredit()));
        }
        return result;
    }

    public PayablesReport payables() {
        Map<UUID, List<JournalLine>> bySupplier = new LinkedHashMap<>();
        Map<UUID, Supplier> suppliers = new HashMap<>();
        for (JournalLine l : journalService.payableLines()) {
            bySupplier.computeIfAbsent(l.getSupplier().getId(), k -> new ArrayList<>()).add(l);
            suppliers.putIfAbsent(l.getSupplier().getId(), l.getSupplier());
        }
        List<Aged> aged = new ArrayList<>();
        Map<Ageing.Bucket, BigDecimal> totals = new EnumMap<>(Ageing.Bucket.class);
        for (Ageing.Bucket b : Ageing.Bucket.values()) {
            totals.put(b, BigDecimal.ZERO);
        }
        BigDecimal total = BigDecimal.ZERO;
        for (Map.Entry<UUID, List<JournalLine>> e : bySupplier.entrySet()) {
            Supplier supplier = suppliers.get(e.getKey());
            Ageing.Result result = ageing(e.getValue(), supplier);
            if (result.balance().signum() == 0) {
                continue;
            }
            aged.add(new Aged(supplier, Payables.open(payableLines(e.getValue())).values(), result));
            result.buckets().forEach((b, v) -> totals.merge(b, v, BigDecimal::add));
            total = total.add(result.balance());
        }
        aged.sort(Comparator.comparing((Aged a) -> a.ageing().balance()).reversed().thenComparing(a -> a.supplier().getName()));
        return new PayablesReport(aged, totals, total);
    }

    public SupplierInvoice findInvoice(UUID id) {
        return invoiceRepo.findDetailedById(id).orElseThrow(() -> new NotFoundException("SupplierInvoice", id));
    }

    public List<SupplierInvoiceLine> invoiceLines(UUID invoiceId) {
        return invoiceLineRepo.findByInvoiceIdOrderByLineNo(invoiceId);
    }

    public SupplierPayment findPayment(UUID id) {
        return paymentRepo.findDetailedById(id).orElseThrow(() -> new NotFoundException("SupplierPayment", id));
    }

    /** Supplier invoices, newest first. */
    public Page<SupplierInvoice> findInvoices(String search, int page, int size) {
        Specification<SupplierInvoice> spec = (root, query, cb) -> {
            Predicate p = cb.conjunction();
            if (StringUtils.hasText(search)) {
                String term = "%" + search.trim().toLowerCase(Locale.ROOT) + "%";
                p = cb.and(p, cb.or(cb.like(cb.lower(root.get("number")), term), cb.like(cb.lower(root.get("supplierRef")), term),
                        cb.like(cb.lower(root.get("supplier").get("name")), term)));
            }
            return p;
        };
        return invoiceRepo.findAll(spec, PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "postedAt")));
    }

    /** Payments to suppliers, newest first. */
    public Page<SupplierPayment> findPayments(String search, int page, int size) {
        Specification<SupplierPayment> spec = (root, query, cb) -> {
            Predicate p = cb.conjunction();
            if (StringUtils.hasText(search)) {
                String term = "%" + search.trim().toLowerCase(Locale.ROOT) + "%";
                p = cb.and(p, cb.or(cb.like(cb.lower(root.get("number")), term), cb.like(cb.lower(cb.coalesce(root.get("reference"), "")), term),
                        cb.like(cb.lower(root.get("supplier").get("name")), term)));
            }
            return p;
        };
        return paymentRepo.findAll(spec, PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "postedAt")));
    }

    // ---------------------------------------------------------------- recording an invoice

    /**
     * Records a supplier's invoice for posted goods receipts not invoiced yet (one currency): its total must be the receipts'
     * value. Due its date plus the supplier's terms. Posts Dr GRNI / Cr Accounts Payable per receipt at its own value.
     */
    @Transactional
    public SupplierInvoice recordInvoice(UUID supplierId, InvoiceForm form) {
        Supplier supplier = supplierRepo.lockById(supplierId).orElseThrow(() -> new NotFoundException("Supplier", supplierId));
        String ref = PartyRules.clean(form.supplierRef());
        if (ref == null) {
            throw BusinessException.onField("supplierRef", "supplierInvoice.ref.required");
        }
        if (ref.length() > 60) {
            throw BusinessException.onField("supplierRef", "supplierInvoice.ref.size");
        }
        if (invoiceRepo.existsBySupplier_IdAndSupplierRefIgnoreCase(supplierId, ref)) {
            throw BusinessException.onField("supplierRef", "supplierInvoice.ref.taken", ref, supplier.getName());
        }
        LocalDate date = form.invoiceDate();
        if (date == null || date.isAfter(today())) {
            throw BusinessException.onField("invoiceDate", "supplierInvoice.date.invalid");
        }
        periods.requireOpen(date, "invoiceDate");
        Map<UUID, Uninvoiced> open = uninvoiced(supplier).stream().collect(Collectors.toMap(u -> u.receipt().getId(), Function.identity()));
        List<Uninvoiced> chosen = new ArrayList<>();
        for (UUID id : form.receiptIds() == null ? List.<UUID>of() : form.receiptIds()) {
            Uninvoiced u = open.get(id);
            if (u == null) {
                throw BusinessException.onField("receiptIds", "supplierInvoice.receipt.notOpen");
            }
            chosen.add(u);
        }
        if (chosen.isEmpty()) {
            throw BusinessException.onField("receiptIds", "supplierInvoice.receipts.required");
        }
        String currency = chosen.get(0).currency();
        if (chosen.stream().anyMatch(u -> !u.currency().equals(currency))) {
            throw BusinessException.onField("receiptIds", "supplierInvoice.receipts.currency");
        }
        BigDecimal total = chosen.stream().map(Uninvoiced::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (form.amount() == null || form.amount().compareTo(total) != 0) {
            throw BusinessException.onField("amount", "supplierInvoice.amount.mismatch", total, currency);
        }
        String notes = PartyRules.clean(form.notes());
        if (notes != null && notes.length() > 255) {
            throw BusinessException.onField("notes", "supplierInvoice.notes.size");
        }
        SupplierInvoice invoice = new SupplierInvoice();
        invoice.setNumber(numbers.next(DocumentType.SUPPLIER_INVOICE));
        invoice.setSupplier(supplier);
        invoice.setSupplierRef(ref);
        invoice.setInvoiceDate(date);
        invoice.setDueDate(date.plusDays(supplier.getPaymentTermsDays()));
        invoice.setCurrencyCode(currency);
        invoice.setAmount(total.setScale(2, RoundingMode.HALF_UP));
        invoice.setBaseAmount(chosen.stream().map(Uninvoiced::base).reduce(BigDecimal.ZERO, BigDecimal::add));
        invoice.setNotes(notes);
        invoice.setPostedAt(LocalDateTime.now(clock));
        invoice.setPostedBy(AppUserPrincipal.currentUsername());
        invoiceRepo.save(invoice);
        List<SupplierInvoiceLine> lines = new ArrayList<>();
        int no = 1;
        for (Uninvoiced u : chosen) {
            SupplierInvoiceLine line = new SupplierInvoiceLine();
            line.setInvoiceId(invoice.getId());
            line.setLineNo(no++);
            line.setGoodsReceiptId(u.receipt().getId());
            line.setReceiptNumber(u.receipt().getNumber());
            line.setAmount(u.amount().setScale(2, RoundingMode.HALF_UP));
            line.setRate(u.rate());
            line.setBaseAmount(u.base());
            lines.add(invoiceLineRepo.save(line));
        }
        postingService.supplierInvoice(invoice, lines);
        return invoice;
    }

    // ---------------------------------------------------------------- paying

    /**
     * Pays a supplier in a currency they are owed in, at most what is owed in it, at today's rate: the oldest items are
     * settled; what they were booked at less the RWF paid is the realised FX gain or loss (ACC-08). Bank transfer and mobile
     * money carry a reference; cash comes out of the main cash vault.
     */
    @Transactional
    public SupplierPayment pay(UUID supplierId, PaymentForm form) {
        PaymentMethod method = form.method();
        if (method != PaymentMethod.BANK_TRANSFER && method != PaymentMethod.CASH && method != PaymentMethod.MOBILE_MONEY) {
            throw BusinessException.onField("method", "supplierPayment.method.required");
        }
        Supplier supplier = supplierRepo.lockById(supplierId).orElseThrow(() -> new NotFoundException("Supplier", supplierId));
        String currency = form.currencyCode();
        Payables.Open open = currency == null ? null : open(supplier).get(currency);
        if (open == null || open.getAmount().signum() <= 0) {
            throw BusinessException.onField("currencyCode", "supplierPayment.nothingOwed", supplier.getName(), currency == null ? "" : currency);
        }
        BigDecimal amount = form.amount();
        if (amount == null || amount.signum() <= 0 || amount.stripTrailingZeros().scale() > 2) {
            throw BusinessException.onField("amount", "supplierPayment.amount.invalid");
        }
        if (amount.compareTo(open.getAmount()) > 0) {
            throw BusinessException.onField("amount", "supplierPayment.tooMuch", amount, currency, open.getAmount());
        }
        String reference = PartyRules.clean(form.reference());
        if (method.needsReference() && reference == null) {
            throw BusinessException.onField("reference", "supplierPayment.refRequired");
        }
        if (reference != null && reference.length() > 60) {
            throw BusinessException.onField("reference", "supplierPayment.refSize");
        }
        String notes = PartyRules.clean(form.notes());
        if (notes != null && notes.length() > 255) {
            throw BusinessException.onField("notes", "supplierPayment.notesSize");
        }
        LocalDate today = today();
        Payables.Settlement settled = Payables.settle(open, amount);
        SupplierPayment payment = new SupplierPayment();
        payment.setSupplier(supplier);
        payment.setPaymentDate(today);
        payment.setMethod(method);
        payment.setReference(method.needsReference() ? reference : null);
        payment.setCurrencyCode(currency);
        payment.setAmount(amount.setScale(2, RoundingMode.HALF_UP));
        if (currency.equals(baseCurrency())) {
            payment.setBaseAmount(payment.getAmount());
        } else {
            ExchangeRateService.AppliedRate rate = rates.rateFor(currency, today);
            payment.setRate(rate.rate());
            payment.setRateDate(rate.rateDate());
            payment.setRateSource(rate.source());
            payment.setBaseAmount(rate.toBase(amount).setScale(2, RoundingMode.HALF_UP));
        }
        payment.setSettledBase(settled.base().setScale(2, RoundingMode.HALF_UP));
        payment.setFxGainLoss(payment.getSettledBase().subtract(payment.getBaseAmount()));
        payment.setNotes(notes);
        payment.setNumber(numbers.next(DocumentType.SUPPLIER_PAYMENT));
        payment.setPostedAt(LocalDateTime.now(clock));
        payment.setPostedBy(AppUserPrincipal.currentUsername());
        paymentRepo.save(payment);
        postingService.supplierPayment(payment);
        return payment;
    }

    // ---------------------------------------------------------------- helpers

    /**
     * The payable lines as owed (credit) or settled (debit), in their currency and in RWF. A month-end revaluation is left
     * out: it is reversed the next day, so its items keep the RWF they were booked at (ACC-08).
     */
    private List<Payables.Line> payableLines(List<JournalLine> lines) {
        String base = baseCurrency();
        List<Payables.Line> result = new ArrayList<>();
        for (JournalLine l : items(lines)) {
            BigDecimal rwf = l.getCredit().subtract(l.getDebit());
            boolean foreign = l.getCurrencyCode() != null && l.getFxAmount() != null;
            BigDecimal amount = foreign ? l.getFxAmount().abs().multiply(BigDecimal.valueOf(rwf.signum())) : rwf;
            result.add(new Payables.Line(l.getEntry().getEntryDate(), foreign ? l.getCurrencyCode() : base, amount, rwf));
        }
        return result;
    }

    /** The RWF ageing at the supplier's terms: what is owed (credits) is the charge, payments (debits) settle it. */
    private Ageing.Result ageing(List<JournalLine> lines, Supplier supplier) {
        return Ageing.of(items(lines).stream().map(l -> new Ageing.Entry(l.getEntry().getEntryDate(), l.getCredit(), l.getDebit())).toList(),
                supplier.getPaymentTermsDays(), today());
    }

    /** The lines of invoices, bills and payments: a revaluation and its reversal (the next day) add up to nothing. */
    private static List<JournalLine> items(List<JournalLine> lines) {
        return lines.stream().filter(l -> l.getEntry().getSourceType() != JournalSource.FX_REVALUATION).toList();
    }

    private String baseCurrency() {
        return currencyRepo.findByBaseCurrencyTrue().map(Currency::getCode).orElse("RWF");
    }

    public LocalDate today() {
        return LocalDate.now(clock);
    }
}
