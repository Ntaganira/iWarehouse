package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.entity.Customer;
import com.ntaganira.heritier.iWarehouse.entity.CustomerPayment;
import com.ntaganira.heritier.iWarehouse.entity.JournalLine;
import com.ntaganira.heritier.iWarehouse.entity.TillSession;
import com.ntaganira.heritier.iWarehouse.enums.DocumentType;
import com.ntaganira.heritier.iWarehouse.enums.PaymentMethod;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.exception.NotFoundException;
import com.ntaganira.heritier.iWarehouse.repository.CustomerPaymentRepository;
import com.ntaganira.heritier.iWarehouse.repository.CustomerRepository;
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
 * - File      : CustomerAccountService.java
 * - Date      : 2026. 10. 09.
 * - User      : Hntaganira
 * - Desc      : Customer accounts (ACC-09). What a customer owes is the receivable account's lines that name them:
 *               their statement, with the balance after each line, and its ageing (Ageing: payments settle the
 *               oldest charges, each due its date plus the customer's payment terms). The aged receivables list every
 *               customer with a balance. A payment on the account (RCT number) settles at most what they owe: cash
 *               into the user's till, mobile money, card or bank transfer; posted at once (Dr that account / Cr the
 *               receivable). Taking it locks the till (cash), then the customer.
 * </pre>
 */
@Service
@Transactional(readOnly = true)
public class CustomerAccountService {

    private final CustomerPaymentRepository repo;
    private final CustomerRepository customerRepo;
    private final JournalService journalService;
    private final TillService tillService;
    private final PostingService postingService;
    private final DocumentNumberService numbers;
    private final Clock clock;

    public CustomerAccountService(CustomerPaymentRepository repo, CustomerRepository customerRepo, JournalService journalService,
                                  TillService tillService, PostingService postingService, DocumentNumberService numbers, Clock clock) {
        this.repo = repo;
        this.customerRepo = customerRepo;
        this.journalService = journalService;
        this.tillService = tillService;
        this.postingService = postingService;
        this.numbers = numbers;
        this.clock = clock;
    }

    /** A line of a customer's statement and the balance after it. */
    public record StatementRow(JournalLine line, BigDecimal balance) {
    }

    /** A customer's account: the statement (newest first), the balance and its ageing. */
    public record Account(List<StatementRow> statement, BigDecimal balance, Ageing.Result ageing) {
    }

    /** A customer of the aged receivables and their ageing. */
    public record Aged(Customer customer, Ageing.Result ageing) {
    }

    /** The aged receivables: each customer with a balance, the largest first, and the totals per bucket. */
    public record Receivables(List<Aged> customers, Map<Ageing.Bucket, BigDecimal> totals, BigDecimal total) {
    }

    /** What a payment on the account says: the amount, how it was paid, its reference, the cash handed over, notes. */
    public record PaymentForm(BigDecimal amount, PaymentMethod method, String reference, BigDecimal cashTendered, String notes) {
    }

    // ---------------------------------------------------------------- reading

    public Account account(Customer customer) {
        List<JournalLine> lines = journalService.customerLines(customer.getId());
        List<StatementRow> rows = new ArrayList<>();
        BigDecimal balance = BigDecimal.ZERO;
        for (JournalLine l : lines) {
            balance = balance.add(l.getDebit()).subtract(l.getCredit());
            rows.add(new StatementRow(l, balance));
        }
        Collections.reverse(rows);
        Ageing.Result ageing = Ageing.of(lines.stream().map(l -> new Ageing.Entry(l.getEntry().getEntryDate(), l.getDebit(), l.getCredit()))
                .toList(), customer.getPaymentTermsDays(), today());
        return new Account(rows, balance, ageing);
    }

    /** What the customer owes now. */
    public BigDecimal owed(UUID customerId) {
        return journalService.receivable(customerId);
    }

    public Receivables receivables() {
        Map<UUID, List<Ageing.Entry>> byCustomer = new HashMap<>();
        for (Object[] row : journalService.receivableEntries()) {
            byCustomer.computeIfAbsent((UUID) row[0], k -> new ArrayList<>())
                    .add(new Ageing.Entry((LocalDate) row[1], (BigDecimal) row[2], (BigDecimal) row[3]));
        }
        Map<UUID, Customer> customers = customerRepo.findAllById(byCustomer.keySet()).stream()
                .collect(Collectors.toMap(Customer::getId, Function.identity()));
        LocalDate today = today();
        List<Aged> aged = new ArrayList<>();
        Map<Ageing.Bucket, BigDecimal> totals = new EnumMap<>(Ageing.Bucket.class);
        for (Ageing.Bucket b : Ageing.Bucket.values()) {
            totals.put(b, BigDecimal.ZERO);
        }
        BigDecimal total = BigDecimal.ZERO;
        for (Map.Entry<UUID, List<Ageing.Entry>> e : byCustomer.entrySet()) {
            Customer customer = customers.get(e.getKey());
            Ageing.Result result = Ageing.of(e.getValue(), customer.getPaymentTermsDays(), today);
            if (result.balance().signum() == 0) {
                continue;
            }
            aged.add(new Aged(customer, result));
            result.buckets().forEach((b, v) -> totals.merge(b, v, BigDecimal::add));
            total = total.add(result.balance());
        }
        aged.sort(Comparator.comparing((Aged a) -> a.ageing().balance()).reversed().thenComparing(a -> a.customer().getName()));
        return new Receivables(aged, totals, total);
    }

    public CustomerPayment findDetailed(UUID id) {
        return repo.findDetailedById(id).orElseThrow(() -> new NotFoundException("CustomerPayment", id));
    }

    /** Payments on customer accounts, newest first. */
    public Page<CustomerPayment> findPage(String search, int page, int size) {
        Specification<CustomerPayment> spec = (root, query, cb) -> {
            Predicate p = cb.conjunction();
            if (StringUtils.hasText(search)) {
                String term = "%" + search.trim().toLowerCase(Locale.ROOT) + "%";
                p = cb.and(p, cb.or(
                        cb.like(cb.lower(root.get("number")), term),
                        cb.like(cb.lower(root.get("customer").get("name")), term),
                        cb.like(cb.lower(root.get("customer").get("code")), term),
                        cb.like(cb.lower(cb.coalesce(root.get("reference"), "")), term),
                        cb.like(cb.lower(root.get("postedBy")), term)));
            }
            return p;
        };
        return repo.findAll(spec, PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "postedAt")));
    }

    /** The cash payments taken in a till session. */
    public List<CustomerPayment> paymentsOf(TillSession session) {
        return repo.findByTillSessionIdOrderByPostedAtAsc(session.getId());
    }

    // ---------------------------------------------------------------- taking a payment

    /**
     * Takes a payment on a customer's account: at most what they owe; cash goes into the user's open till (what was handed
     * over gives the change), the other ways carry their reference. Posts Dr that account / Cr the customer's receivable.
     */
    @Transactional
    public CustomerPayment receive(UUID customerId, PaymentForm form) {
        PaymentMethod method = form.method();
        if (method == null || method == PaymentMethod.CREDIT) {
            throw BusinessException.onField("method", "customerPayment.method.required");
        }
        TillSession till = method == PaymentMethod.CASH ? tillService.lockCurrent() : null;
        Customer customer = customerRepo.lockById(customerId).orElseThrow(() -> new NotFoundException("Customer", customerId));
        BigDecimal amount = form.amount();
        if (amount == null || amount.signum() <= 0 || amount.stripTrailingZeros().scale() > 2) {
            throw BusinessException.onField("amount", "customerPayment.amount.invalid");
        }
        BigDecimal owed = owed(customerId);
        if (owed.signum() <= 0) {
            throw BusinessException.of("customerPayment.nothingOwed", customer.getName());
        }
        if (amount.compareTo(owed) > 0) {
            throw BusinessException.onField("amount", "customerPayment.tooMuch", amount, owed);
        }
        String reference = PartyRules.clean(form.reference());
        if (method.needsReference() && reference == null) {
            throw BusinessException.onField("reference", "customerPayment.refRequired");
        }
        if (reference != null && reference.length() > 60) {
            throw BusinessException.onField("reference", "customerPayment.refSize");
        }
        BigDecimal tendered = null;
        if (method == PaymentMethod.CASH) {
            tendered = form.cashTendered() == null ? amount : form.cashTendered();
            if (tendered.compareTo(amount) < 0 || tendered.stripTrailingZeros().scale() > 2) {
                throw BusinessException.onField("cashTendered", "customerPayment.tenderedShort", amount);
            }
        }
        String notes = PartyRules.clean(form.notes());
        if (notes != null && notes.length() > 255) {
            throw BusinessException.onField("notes", "customerPayment.notesSize");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        CustomerPayment payment = new CustomerPayment();
        payment.setNumber(numbers.next(DocumentType.RECEIPT));
        payment.setCustomer(customer);
        payment.setPaymentDate(now.toLocalDate());
        payment.setMethod(method);
        payment.setAmount(amount.setScale(2));
        payment.setReference(method.needsReference() ? reference : null);
        payment.setTillSessionId(till == null ? null : till.getId());
        payment.setCashTendered(tendered == null ? null : tendered.setScale(2));
        payment.setNotes(notes);
        payment.setPostedAt(now);
        payment.setPostedBy(AppUserPrincipal.currentUsername());
        repo.save(payment);
        postingService.customerPayment(payment);
        return payment;
    }

    public LocalDate today() {
        return LocalDate.now(clock);
    }
}
