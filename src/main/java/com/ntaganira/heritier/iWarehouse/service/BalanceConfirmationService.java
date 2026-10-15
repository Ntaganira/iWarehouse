package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.entity.BalanceConfirmation;
import com.ntaganira.heritier.iWarehouse.entity.Customer;
import com.ntaganira.heritier.iWarehouse.enums.AccountKey;
import com.ntaganira.heritier.iWarehouse.enums.ConfirmationStatus;
import com.ntaganira.heritier.iWarehouse.enums.DocumentType;
import com.ntaganira.heritier.iWarehouse.enums.NotificationKind;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.exception.NotFoundException;
import com.ntaganira.heritier.iWarehouse.repository.AccountRepository;
import com.ntaganira.heritier.iWarehouse.repository.BalanceConfirmationRepository;
import com.ntaganira.heritier.iWarehouse.repository.CustomerRepository;
import com.ntaganira.heritier.iWarehouse.repository.JournalLineRepository;
import com.ntaganira.heritier.iWarehouse.security.AppUserPrincipal;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : BalanceConfirmationService.java
 * - Date      : 2026. 10. 15.
 * - User      : Hntaganira
 * - Desc      : Independent confirmation of customer balances (SDP 10-11, BP Table 9 and 11; Q.07, and what completes
 *               Q.09 and Q.11: a credit sale for goods paid in cash, or one customer's money put on another's invoice,
 *               shows as a balance the customer disputes). The Internal Controller sends a confirmation of the balance
 *               on a day (the ledger's, at that day) to one customer or to every customer with a balance, one waiting per
 *               customer; the reply is recorded once, agreed or disputed with the customer's balance and explanation,
 *               and a dispute is told to ALERT_DAILY_CLOSE holders.
 * </pre>
 */
@Service
@Transactional(readOnly = true)
public class BalanceConfirmationService {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final BalanceConfirmationRepository repo;
    private final CustomerRepository customerRepo;
    private final JournalLineRepository lineRepo;
    private final AccountRepository accountRepo;
    private final DocumentNumberService numbers;
    private final Notifier notifier;
    private final Clock clock;

    public BalanceConfirmationService(BalanceConfirmationRepository repo, CustomerRepository customerRepo, JournalLineRepository lineRepo,
                                      AccountRepository accountRepo, DocumentNumberService numbers, Notifier notifier, Clock clock) {
        this.repo = repo;
        this.customerRepo = customerRepo;
        this.lineRepo = lineRepo;
        this.accountRepo = accountRepo;
        this.numbers = numbers;
        this.notifier = notifier;
        this.clock = clock;
    }

    /** A customer a confirmation may go to, with what they owe today. */
    public record Candidate(Customer customer, BigDecimal balance) {
    }

    /** A reply: agreed, or the customer's balance and their explanation; the day it came back. */
    public record Reply(boolean agreed, LocalDate repliedOn, BigDecimal theirBalance, String note) {
    }

    // ---------------------------------------------------------------- reading

    /** All, or those in a status, newest first. */
    public Page<BalanceConfirmation> findPage(String status, int page, int size) {
        PageRequest request = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "sentAt").and(Sort.by(Sort.Direction.DESC, "number")));
        for (ConfirmationStatus s : ConfirmationStatus.values()) {
            if (s.name().equals(status)) {
                return repo.findByStatus(s, request);
            }
        }
        return repo.findAllBy(request);
    }

    public Page<BalanceConfirmation> ofCustomer(UUID customerId, int page, int size) {
        return repo.findByCustomer_Id(customerId, PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "sentAt")));
    }

    public BalanceConfirmation findDetailed(UUID id) {
        return repo.findDetailedById(id).orElseThrow(() -> new NotFoundException("BalanceConfirmation", id));
    }

    public long countByStatus(ConfirmationStatus status) {
        return repo.countByStatus(status);
    }

    /** What a customer owed on a day: their lines on the receivable account dated up to it. */
    public BigDecimal balanceAt(UUID customerId, LocalDate asOf) {
        return lineRepo.balanceOfCustomerAt(receivable(), customerId, asOf);
    }

    public LocalDate today() {
        return LocalDate.now(clock);
    }

    /** Every active customer but the walk-in, with today's balance: a zero balance is confirmed too (one the customer says they paid). */
    public List<Candidate> candidates() {
        Map<UUID, BigDecimal> balances = balances(today());
        return customerRepo.findByEnabledTrueOrderByNameAsc().stream().filter(c -> !c.isDefaultCustomer())
                .map(c -> new Candidate(c, balances.getOrDefault(c.getId(), BigDecimal.ZERO))).toList();
    }

    // ---------------------------------------------------------------- sending and replies

    /** Sends a confirmation of a customer's balance on a day (up to today): refused while one waits for their reply. */
    @Transactional
    public BalanceConfirmation send(UUID customerId, LocalDate asOf) {
        if (asOf == null || asOf.isAfter(today())) {
            throw BusinessException.onField("asOf", "confirmation.asOf.invalid");
        }
        Customer customer = customerRepo.lockById(customerId).orElseThrow(() -> BusinessException.onField("customerId", "confirmation.customer.required"));
        if (repo.existsByCustomer_IdAndStatus(customerId, ConfirmationStatus.SENT)) {
            throw BusinessException.onField("customerId", "confirmation.waiting", customer.getName());
        }
        return create(customer, asOf, balanceAt(customerId, asOf));
    }

    /** Sends a confirmation to every customer with a balance on the day and none waiting; how many were sent. */
    @Transactional
    public int sendAll(LocalDate asOf) {
        if (asOf == null || asOf.isAfter(today())) {
            throw BusinessException.onField("asOf", "confirmation.asOf.invalid");
        }
        int sent = 0;
        for (Map.Entry<UUID, BigDecimal> e : balances(asOf).entrySet()) {
            if (e.getValue().signum() == 0 || repo.existsByCustomer_IdAndStatus(e.getKey(), ConfirmationStatus.SENT)) {
                continue;
            }
            Customer customer = customerRepo.lockById(e.getKey()).orElseThrow();
            create(customer, asOf, e.getValue());
            sent++;
        }
        return sent;
    }

    /** Records the customer's reply, once: agreed, or disputed with their balance and explanation (told to the Director of Finance). */
    @Transactional
    public BalanceConfirmation reply(UUID id, Reply reply) {
        BalanceConfirmation c = repo.lockById(id).orElseThrow(() -> new NotFoundException("BalanceConfirmation", id));
        if (!c.isWaiting()) {
            throw BusinessException.of("confirmation.replied", c.getNumber());
        }
        if (reply.repliedOn() == null || reply.repliedOn().isAfter(today()) || reply.repliedOn().isBefore(c.getSentAt().toLocalDate())) {
            throw BusinessException.onField("repliedOn", "confirmation.repliedOn.invalid");
        }
        String note = PartyRules.clean(reply.note());
        if (note != null && note.length() > 500) {
            throw BusinessException.onField("replyNote", "confirmation.note.size");
        }
        if (!reply.agreed()) {
            if (reply.theirBalance() == null || reply.theirBalance().stripTrailingZeros().scale() > 2) {
                throw BusinessException.onField("replyBalance", "confirmation.theirBalance.required");
            }
            if (note == null) {
                throw BusinessException.onField("replyNote", "confirmation.note.required");
            }
        }
        AppUserPrincipal user = AppUserPrincipal.current().orElseThrow(() -> BusinessException.of("till.noUser"));
        c.setRepliedOn(reply.repliedOn());
        c.setReplyBalance(reply.agreed() ? c.getBalance() : reply.theirBalance().setScale(2));
        c.setReplyNote(note);
        c.setReplyRecordedBy(user.getUsername());
        c.setReplyRecordedById(user.getId());
        c.setReplyRecordedAt(LocalDateTime.now(clock));
        c.setStatus(reply.agreed() ? ConfirmationStatus.AGREED : ConfirmationStatus.DISAGREED);
        if (!reply.agreed()) {
            notifier.holders("ALERT_DAILY_CLOSE", user.getId(), NotificationKind.CLOSE, "notify.confirmation.disputed",
                    "notify.confirmation.disputedText", "/accounting/confirmations/" + c.getId(), c.getCustomer().getName(),
                    c.getAsOf().format(DAY), CashDepositService.rwf(c.getBalance()), CashDepositService.rwf(c.getReplyBalance()));
        }
        return c;
    }

    // ---------------------------------------------------------------- helpers

    private BalanceConfirmation create(Customer customer, LocalDate asOf, BigDecimal balance) {
        AppUserPrincipal user = AppUserPrincipal.current().orElseThrow(() -> BusinessException.of("till.noUser"));
        BalanceConfirmation c = new BalanceConfirmation();
        c.setNumber(numbers.next(DocumentType.BALANCE_CONFIRMATION));
        c.setCustomer(customer);
        c.setAsOf(asOf);
        c.setBalance(balance.setScale(2));
        c.setSentBy(user.getUsername());
        c.setSentById(user.getId());
        c.setSentAt(LocalDateTime.now(clock));
        return repo.save(c);
    }

    /** What each customer owed on a day (those with lines on the receivable account). */
    private Map<UUID, BigDecimal> balances(LocalDate asOf) {
        Map<UUID, BigDecimal> balances = new LinkedHashMap<>();
        for (Object[] row : lineRepo.customerMovements(receivable(), asOf)) {
            balances.merge((UUID) row[0], ((BigDecimal) row[3]).subtract((BigDecimal) row[4]), BigDecimal::add);
        }
        return balances;
    }

    private UUID receivable() {
        return accountRepo.findBySystemKey(AccountKey.RECEIVABLE).orElseThrow(() -> new IllegalStateException("No receivable account")).getId();
    }
}
