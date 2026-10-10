package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.entity.Account;
import com.ntaganira.heritier.iWarehouse.entity.JournalEntry;
import com.ntaganira.heritier.iWarehouse.entity.JournalLine;
import com.ntaganira.heritier.iWarehouse.entity.Product;
import com.ntaganira.heritier.iWarehouse.enums.AccountKey;
import com.ntaganira.heritier.iWarehouse.enums.DocumentType;
import com.ntaganira.heritier.iWarehouse.enums.JournalSource;
import com.ntaganira.heritier.iWarehouse.exception.NotFoundException;
import com.ntaganira.heritier.iWarehouse.repository.AccountRepository;
import com.ntaganira.heritier.iWarehouse.repository.CustomerRepository;
import com.ntaganira.heritier.iWarehouse.repository.DriverRepository;
import com.ntaganira.heritier.iWarehouse.repository.JournalEntryRepository;
import com.ntaganira.heritier.iWarehouse.repository.JournalLineRepository;
import com.ntaganira.heritier.iWarehouse.repository.ProductRepository;
import com.ntaganira.heritier.iWarehouse.repository.SupplierRepository;
import com.ntaganira.heritier.iWarehouse.security.AppUserPrincipal;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
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
 * - File      : JournalService.java
 * - Date      : 2026. 10. 09.
 * - User      : Hntaganira
 * - Desc      : Saves the journals the posting rules build (ACC-04), inside the event's transaction, numbered
 *               JV-WH-2026-000001; and reads them back: the journal list, a journal with its lines, an
 *               account's ledger, the trial balance (ACC-11) and the inventory account against the stock
 *               valuation, per glass (AT-10).
 * </pre>
 */
@Service
@Transactional(readOnly = true)
public class JournalService {

    private final JournalEntryRepository entryRepo;
    private final JournalLineRepository lineRepo;
    private final AccountRepository accountRepo;
    private final ProductRepository productRepo;
    private final SupplierRepository supplierRepo;
    private final CustomerRepository customerRepo;
    private final DriverRepository driverRepo;
    private final StockSummaryService summaryService;
    private final DocumentNumberService numbers;
    private final PeriodLock periods;
    private final Clock clock;

    public JournalService(JournalEntryRepository entryRepo, JournalLineRepository lineRepo, AccountRepository accountRepo,
                          ProductRepository productRepo, SupplierRepository supplierRepo, CustomerRepository customerRepo,
                          DriverRepository driverRepo, StockSummaryService summaryService, DocumentNumberService numbers, PeriodLock periods, Clock clock) {
        this.entryRepo = entryRepo;
        this.lineRepo = lineRepo;
        this.accountRepo = accountRepo;
        this.productRepo = productRepo;
        this.supplierRepo = supplierRepo;
        this.customerRepo = customerRepo;
        this.driverRepo = driverRepo;
        this.summaryService = summaryService;
        this.numbers = numbers;
        this.periods = periods;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- posting

    /**
     * Saves a journal in the caller's transaction (MANDATORY: it belongs to the event it records). An empty
     * journal is not saved (null). A journal that does not balance is a programming error: refused here, and
     * by the database at commit.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public JournalEntry post(Journal journal) {
        List<Journal.Line> lines = journal.lines();
        if (lines.isEmpty()) {
            return null;
        }
        if (!journal.isBalanced()) {
            throw new IllegalStateException("Journal for " + journal.sourceNumber() + " does not balance: debits "
                    + journal.debits() + ", credits " + journal.credits());
        }
        Map<AccountKey, Account> accounts = accountRepo.findBySystemKeyIsNotNull().stream()
                .collect(Collectors.toMap(Account::getSystemKey, Function.identity()));
        JournalEntry entry = newEntry(journal.source(), journal.sourceId(), journal.sourceNumber(), journal.date(), journal.description(),
                journal.debits(), journal.reversesId());
        int no = 1;
        for (Journal.Line l : lines) {
            Account account = accounts.get(l.account());
            if (account == null) {
                throw new IllegalStateException("No account has the system key " + l.account());
            }
            JournalLine line = new JournalLine();
            line.setEntry(entry);
            line.setLineNo(no++);
            line.setAccount(account);
            line.setDebit(l.debit());
            line.setCredit(l.credit());
            line.setMemo(l.memo());
            line.setProduct(l.productId() == null ? null : productRepo.getReferenceById(l.productId()));
            line.setSupplier(l.supplierId() == null ? null : supplierRepo.getReferenceById(l.supplierId()));
            line.setCustomer(l.customerId() == null ? null : customerRepo.getReferenceById(l.customerId()));
            line.setDriver(l.driverId() == null ? null : driverRepo.getReferenceById(l.driverId()));
            if (l.fx() != null) {
                line.setCurrencyCode(l.fx().currencyCode());
                line.setFxAmount(l.fx().amount());
                line.setRate(l.fx().rate());
            }
            lineRepo.save(line);
        }
        return entry;
    }

    /** A line by account (a manual journal's, ACC-05): a debit or a credit, never both, and a memo. */
    public record AccountLine(Account account, BigDecimal debit, BigDecimal credit, String memo) {
    }

    /**
     * Saves a journal given by account, not by posting rule (a manual journal, ACC-05), in the caller's transaction. At least
     * two lines, each a debit or a credit with 2 decimals, balanced: anything else is a programming error, refused here and by
     * the database at commit.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public JournalEntry postLines(JournalSource source, UUID sourceId, String sourceNumber, LocalDate date, String description,
                                  List<AccountLine> lines) {
        BigDecimal debits = BigDecimal.ZERO;
        BigDecimal credits = BigDecimal.ZERO;
        for (AccountLine l : lines) {
            if (l.account() == null || (l.debit().signum() > 0) == (l.credit().signum() > 0) || l.debit().signum() < 0 || l.credit().signum() < 0) {
                throw new IllegalStateException("Journal for " + sourceNumber + ": a line is not a debit or a credit");
            }
            debits = debits.add(l.debit());
            credits = credits.add(l.credit());
        }
        if (lines.size() < 2 || debits.compareTo(credits) != 0) {
            throw new IllegalStateException("Journal for " + sourceNumber + " does not balance: debits " + debits + ", credits " + credits);
        }
        JournalEntry entry = newEntry(source, sourceId, sourceNumber, date, description, debits.setScale(Journal.SCALE, RoundingMode.HALF_UP), null);
        int no = 1;
        for (AccountLine l : lines) {
            JournalLine line = new JournalLine();
            line.setEntry(entry);
            line.setLineNo(no++);
            line.setAccount(l.account());
            line.setDebit(l.debit().setScale(Journal.SCALE, RoundingMode.HALF_UP));
            line.setCredit(l.credit().setScale(Journal.SCALE, RoundingMode.HALF_UP));
            line.setMemo(l.memo());
            lineRepo.save(line);
        }
        return entry;
    }

    /**
     * Reverses a posted journal (ACC-05: never deleted): a new journal on {@code date}, of the same event and document, every
     * line the other way with the same account, glass, supplier, customer, memo and foreign amount, naming the journal it
     * reverses. A journal is reversed once.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public JournalEntry reverse(JournalEntry original, LocalDate date, String description) {
        if (!entryRepo.findByReversesIdOrderByNumber(original.getId()).isEmpty()) {
            throw new IllegalStateException(original.getNumber() + " is reversed already");
        }
        List<JournalLine> lines = lineRepo.findByEntry_IdOrderByLineNo(original.getId());
        JournalEntry entry = newEntry(original.getSourceType(), original.getSourceId(), original.getSourceNumber(), date, description,
                original.getTotal(), original.getId());
        for (JournalLine l : lines) {
            JournalLine line = new JournalLine();
            line.setEntry(entry);
            line.setLineNo(l.getLineNo());
            line.setAccount(l.getAccount());
            line.setDebit(l.getCredit());
            line.setCredit(l.getDebit());
            line.setMemo(l.getMemo());
            line.setProduct(l.getProduct());
            line.setSupplier(l.getSupplier());
            line.setCustomer(l.getCustomer());
            line.setDriver(l.getDriver());
            line.setCurrencyCode(l.getCurrencyCode());
            line.setFxAmount(l.getFxAmount());
            line.setRate(l.getRate());
            lineRepo.save(line);
        }
        return entry;
    }

    /**
     * A journal's header, numbered JV-WH-2026-000001 and saved: posted now by the current user. Refused on a closed month
     * (ACC-10): the event that posts it is refused with it.
     */
    private JournalEntry newEntry(JournalSource source, UUID sourceId, String sourceNumber, LocalDate date, String description,
                                  BigDecimal total, UUID reversesId) {
        periods.requireOpen(date);
        Optional<AppUserPrincipal> user = AppUserPrincipal.current();
        JournalEntry entry = new JournalEntry();
        entry.setNumber(numbers.next(DocumentType.JOURNAL));
        entry.setEntryDate(date);
        entry.setSourceType(source);
        entry.setSourceId(sourceId);
        entry.setSourceNumber(sourceNumber);
        entry.setDescription(description);
        entry.setTotal(total);
        entry.setReversesId(reversesId);
        entry.setPostedAt(LocalDateTime.now(clock));
        entry.setUserId(user.map(AppUserPrincipal::getId).orElse(null));
        entry.setUsername(user.map(AppUserPrincipal::getUsername).orElse("system"));
        entryRepo.save(entry);
        return entry;
    }

    // ---------------------------------------------------------------- reading

    public Page<JournalEntry> findPage(String search, String source, int page, int size) {
        Specification<JournalEntry> spec = (root, query, cb) -> {
            Predicate p = cb.conjunction();
            if (StringUtils.hasText(search)) {
                String term = "%" + search.trim().toLowerCase(Locale.ROOT) + "%";
                p = cb.and(p, cb.or(
                        cb.like(cb.lower(root.get("number")), term),
                        cb.like(cb.lower(cb.coalesce(root.get("sourceNumber"), "")), term),
                        cb.like(cb.lower(root.get("description")), term),
                        cb.like(cb.lower(root.get("username")), term)));
            }
            JournalSource type = source(source);
            if (type != null) {
                p = cb.and(p, cb.equal(root.get("sourceType"), type));
            }
            return p;
        };
        return entryRepo.findAll(spec, PageRequest.of(page, size,
                Sort.by(Sort.Direction.DESC, "postedAt").and(Sort.by(Sort.Direction.DESC, "number"))));
    }

    public JournalEntry findById(UUID id) {
        return entryRepo.findById(id).orElseThrow(() -> new NotFoundException("JournalEntry", id));
    }

    /** A journal's lines with their accounts, glass and suppliers. */
    public List<JournalLine> lines(UUID entryId) {
        return lineRepo.findByEntry_IdOrderByLineNo(entryId);
    }

    /** The journals posted for a document (a shipment: each posting and its claim), oldest first. */
    public List<JournalEntry> forSource(UUID sourceId, JournalSource... types) {
        return entryRepo.findBySourceTypeInAndSourceIdOrderByPostedAtAscNumberAsc(List.of(types), sourceId);
    }

    /** The journals that reverse this one. */
    public List<JournalEntry> reversalsOf(UUID entryId) {
        return entryRepo.findByReversesIdOrderByNumber(entryId);
    }

    public boolean hasJournal(JournalSource type, UUID sourceId) {
        return entryRepo.existsBySourceTypeAndSourceId(type, sourceId);
    }

    /** An account's lines, newest journal first. */
    public Page<JournalLine> ledger(UUID accountId, int page, int size) {
        return lineRepo.findLedger(accountId, PageRequest.of(page, size,
                Sort.by(Sort.Direction.DESC, "entry.entryDate").and(Sort.by(Sort.Direction.DESC, "entry.number"))
                        .and(Sort.by(Sort.Direction.ASC, "lineNo"))));
    }

    /** A customer's lines on the receivable account (their statement, ACC-09), in the order they were posted. */
    public List<JournalLine> customerLines(UUID customerId) {
        return lineRepo.findCustomerLines(receivableAccount().getId(), customerId);
    }

    /** Rows of (customer id, date, debit, credit) of the receivable account's lines that name a customer. */
    public List<Object[]> receivableEntries() {
        return lineRepo.customerEntries(receivableAccount().getId());
    }

    /** A supplier's lines on the payable account (their statement, ACC-09), in the order they were posted. */
    public List<JournalLine> supplierLines(UUID supplierId) {
        return lineRepo.findSupplierLines(account(AccountKey.PAYABLE).getId(), supplierId);
    }

    /** Every payable line that names a supplier, in the order they were posted (the payables report). */
    public List<JournalLine> payableLines() {
        return lineRepo.findSupplierLines(account(AccountKey.PAYABLE).getId());
    }

    /** The goods receipts' lines on Goods Received Not Invoiced (what each receipt left to invoice). */
    public List<JournalLine> grniOfReceipts(Collection<UUID> receiptIds) {
        return receiptIds.isEmpty() ? List.of()
                : lineRepo.findOfSources(account(AccountKey.GRNI).getId(), JournalSource.GOODS_RECEIPT, receiptIds);
    }

    private Account account(AccountKey key) {
        return accountRepo.findBySystemKey(key).orElseThrow(() -> new IllegalStateException("No account " + key));
    }

    private Account receivableAccount() {
        return accountRepo.findBySystemKey(AccountKey.RECEIVABLE).orElseThrow(() -> new IllegalStateException("No receivable account"));
    }

    /** What a customer owes: the receivable account's balance on their lines (POS-05). */
    public BigDecimal receivable(UUID customerId) {
        Account receivable = accountRepo.findBySystemKey(AccountKey.RECEIVABLE)
                .orElseThrow(() -> new IllegalStateException("No receivable account"));
        return lineRepo.balanceOfCustomer(receivable.getId(), customerId);
    }

    /** Debits less credits of every account with lines. */
    public Map<UUID, BigDecimal> netByAccount() {
        Map<UUID, BigDecimal> net = new HashMap<>();
        for (Object[] row : lineRepo.balances()) {
            net.put((UUID) row[0], ((BigDecimal) row[1]).subtract((BigDecimal) row[2]));
        }
        return net;
    }

    /** Trial balance of the journals dated up to a day (ACC-11). */
    public TrialBalance.Result trialBalance(LocalDate asOf) {
        return TrialBalance.of(accountRepo.findAll(), lineRepo.balancesAsOf(asOf));
    }

    public LocalDate today() {
        return LocalDate.now(clock);
    }

    /** The opening stock journal, if the ledger has started. */
    public Optional<JournalEntry> openingStock() {
        return entryRepo.findFirstBySourceType(JournalSource.OPENING_STOCK);
    }

    /** A glass whose inventory account balance differs from its stock value. */
    public record GlassDifference(Product product, BigDecimal ledger, BigDecimal valuation) {

        public BigDecimal getDifference() {
            return ledger.subtract(valuation);
        }
    }

    /** The inventory account against the stock valuation (AT-10), in total and per glass. */
    public record InventoryCheck(BigDecimal ledger, BigDecimal valuation, List<GlassDifference> differences) {

        public BigDecimal getDifference() {
            return ledger.subtract(valuation);
        }

        public boolean isMatching() {
            return differences.isEmpty() && getDifference().signum() == 0;
        }
    }

    /** The inventory account's balance per glass (a line without a glass under null). */
    public Map<UUID, BigDecimal> inventoryByProduct() {
        Account inventory = accountRepo.findBySystemKey(AccountKey.INVENTORY)
                .orElseThrow(() -> new IllegalStateException("No inventory account"));
        Map<UUID, BigDecimal> ledger = new HashMap<>();
        for (Object[] row : lineRepo.balanceByProduct(inventory.getId())) {
            ledger.put((UUID) row[0], (BigDecimal) row[1]);
        }
        return ledger;
    }

    public InventoryCheck inventoryCheck() {
        Map<UUID, BigDecimal> ledger = inventoryByProduct();
        Map<UUID, BigDecimal> valuation = summaryService.valueByProduct();
        Set<UUID> glass = new HashSet<>(ledger.keySet());
        glass.addAll(valuation.keySet());
        Map<UUID, Product> products = productRepo.findAllById(glass.stream().filter(Objects::nonNull).toList()).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));
        List<GlassDifference> differences = new ArrayList<>();
        for (UUID id : glass) {
            BigDecimal l = ledger.getOrDefault(id, BigDecimal.ZERO);
            BigDecimal v = valuation.getOrDefault(id, BigDecimal.ZERO);
            if (l.compareTo(v) != 0) {
                differences.add(new GlassDifference(id == null ? null : products.get(id), l, v));
            }
        }
        differences.sort(Comparator.comparing(d -> d.product() == null ? "" : d.product().getCode()));
        BigDecimal ledgerTotal = ledger.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal valuationTotal = valuation.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return new InventoryCheck(ledgerTotal, valuationTotal, differences);
    }

    private static JournalSource source(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return JournalSource.valueOf(value);
        } catch (IllegalArgumentException e) {
            return null; // unknown source: no filter
        }
    }
}
