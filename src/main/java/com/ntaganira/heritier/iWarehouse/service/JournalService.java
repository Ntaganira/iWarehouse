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
    private final StockSummaryService summaryService;
    private final DocumentNumberService numbers;
    private final Clock clock;

    public JournalService(JournalEntryRepository entryRepo, JournalLineRepository lineRepo, AccountRepository accountRepo,
                          ProductRepository productRepo, SupplierRepository supplierRepo, CustomerRepository customerRepo,
                          StockSummaryService summaryService, DocumentNumberService numbers, Clock clock) {
        this.entryRepo = entryRepo;
        this.lineRepo = lineRepo;
        this.accountRepo = accountRepo;
        this.productRepo = productRepo;
        this.supplierRepo = supplierRepo;
        this.customerRepo = customerRepo;
        this.summaryService = summaryService;
        this.numbers = numbers;
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
        Optional<AppUserPrincipal> user = AppUserPrincipal.current();
        JournalEntry entry = new JournalEntry();
        entry.setNumber(numbers.next(DocumentType.JOURNAL));
        entry.setEntryDate(journal.date());
        entry.setSourceType(journal.source());
        entry.setSourceId(journal.sourceId());
        entry.setSourceNumber(journal.sourceNumber());
        entry.setDescription(journal.description());
        entry.setTotal(journal.debits());
        entry.setPostedAt(LocalDateTime.now(clock));
        entry.setUserId(user.map(AppUserPrincipal::getId).orElse(null));
        entry.setUsername(user.map(AppUserPrincipal::getUsername).orElse("system"));
        entryRepo.save(entry);
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
            if (l.fx() != null) {
                line.setCurrencyCode(l.fx().currencyCode());
                line.setFxAmount(l.fx().amount());
                line.setRate(l.fx().rate());
            }
            lineRepo.save(line);
        }
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
