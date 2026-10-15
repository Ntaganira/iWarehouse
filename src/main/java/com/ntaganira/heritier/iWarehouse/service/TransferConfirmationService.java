package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.entity.PaymentConfirmation;
import com.ntaganira.heritier.iWarehouse.entity.SalesInvoice;
import com.ntaganira.heritier.iWarehouse.entity.SalesPayment;
import com.ntaganira.heritier.iWarehouse.enums.NotificationKind;
import com.ntaganira.heritier.iWarehouse.enums.PaymentMethod;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.exception.NotFoundException;
import com.ntaganira.heritier.iWarehouse.repository.PaymentConfirmationRepository;
import com.ntaganira.heritier.iWarehouse.repository.SalesInvoiceRepository;
import com.ntaganira.heritier.iWarehouse.repository.SalesPaymentRepository;
import com.ntaganira.heritier.iWarehouse.security.AppUserPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : TransferConfirmationService.java
 * - Date      : 2026. 10. 15.
 * - User      : Hntaganira
 * - Desc      : Bank transfers paid on invoices, confirmed by Finance once the money is on the bank statement (SDP 14,
 *               P.05): with the Settings' release.transfer-confirmed, goods paid that way leave only then
 *               (SalesService.requireTransfersConfirmed). Confirmed once, never by who took the payment; Dispatch is
 *               told.
 * </pre>
 */
@Service
@Transactional(readOnly = true)
public class TransferConfirmationService {

    /** Transfers of so many days are listed. */
    static final int DAYS = 90;

    private final SalesPaymentRepository paymentRepo;
    private final PaymentConfirmationRepository repo;
    private final SalesInvoiceRepository invoiceRepo;
    private final Notifier notifier;
    private final Clock clock;

    public TransferConfirmationService(SalesPaymentRepository paymentRepo, PaymentConfirmationRepository repo,
                                       SalesInvoiceRepository invoiceRepo, Notifier notifier, Clock clock) {
        this.paymentRepo = paymentRepo;
        this.repo = repo;
        this.invoiceRepo = invoiceRepo;
        this.notifier = notifier;
        this.clock = clock;
    }

    /** A transfer paid on an invoice, its invoice, and its confirmation once given. */
    public record Transfer(SalesPayment payment, SalesInvoice invoice, PaymentConfirmation confirmation) {

        public boolean isConfirmed() {
            return confirmation != null;
        }
    }

    /** The transfers of the last 90 days: those waiting first (oldest first), then those confirmed (newest first). */
    public List<Transfer> recent(boolean waitingOnly) {
        List<SalesPayment> payments = paymentRepo.findByMethodAndCreatedAtAfterOrderByCreatedAtAsc(PaymentMethod.BANK_TRANSFER,
                LocalDateTime.now(clock).minusDays(DAYS));
        List<Transfer> transfers = transfers(payments);
        List<Transfer> list = new ArrayList<>(transfers.stream().filter(t -> !t.isConfirmed()).toList());
        if (!waitingOnly) {
            List<Transfer> confirmed = new ArrayList<>(transfers.stream().filter(Transfer::isConfirmed).toList());
            Collections.reverse(confirmed);
            list.addAll(confirmed);
        }
        return list;
    }

    /** An invoice's transfers (its page). */
    public List<Transfer> ofInvoice(UUID invoiceId) {
        return transfers(paymentRepo.findByInvoiceIdAndMethod(invoiceId, PaymentMethod.BANK_TRANSFER));
    }

    public long waiting() {
        return recent(true).size();
    }

    /** Confirms a transfer has arrived, with the bank's reference: once, never by who took the payment. */
    @Transactional
    public PaymentConfirmation confirm(UUID paymentId, String bankReference, String note) {
        SalesPayment payment = paymentRepo.findById(paymentId).orElseThrow(() -> new NotFoundException("SalesPayment", paymentId));
        if (payment.getMethod() != PaymentMethod.BANK_TRANSFER) {
            throw BusinessException.of("bankTransfer.notTransfer");
        }
        if (repo.findBySalesPaymentId(paymentId).isPresent()) {
            throw BusinessException.of("bankTransfer.done");
        }
        AppUserPrincipal user = AppUserPrincipal.current().orElseThrow(() -> BusinessException.of("till.noUser"));
        if (user.getUsername().equals(payment.getUsername())) {
            throw BusinessException.of("bankTransfer.own");
        }
        String reference = PartyRules.clean(bankReference);
        if (reference != null && reference.length() > 60) {
            throw BusinessException.onField("bankReference", "bankTransfer.reference.size");
        }
        String clean = PartyRules.clean(note);
        if (clean != null && clean.length() > 255) {
            throw BusinessException.onField("note", "bankTransfer.note.size");
        }
        PaymentConfirmation c = new PaymentConfirmation();
        c.setSalesPaymentId(paymentId);
        c.setInvoiceId(payment.getInvoiceId());
        c.setBankReference(reference);
        c.setNote(clean);
        c.setCreatedAt(LocalDateTime.now(clock));
        c.setUserId(user.getId());
        c.setUsername(user.getUsername());
        repo.save(c);
        SalesInvoice invoice = invoiceRepo.findById(payment.getInvoiceId()).orElseThrow();
        notifier.holders("PREPARE_DELIVERY", user.getId(), NotificationKind.DELIVERY, "notify.transfer.confirmed", "notify.transfer.confirmedText",
                "/invoices/" + invoice.getId(), invoice.getNumber(), CashDepositService.rwf(payment.getAmount()));
        return c;
    }

    private List<Transfer> transfers(List<SalesPayment> payments) {
        if (payments.isEmpty()) {
            return List.of();
        }
        Map<UUID, PaymentConfirmation> confirmed = repo.findBySalesPaymentIdIn(payments.stream().map(SalesPayment::getId).toList()).stream()
                .collect(Collectors.toMap(PaymentConfirmation::getSalesPaymentId, Function.identity()));
        Map<UUID, SalesInvoice> invoices = invoiceRepo.findByIdIn(payments.stream().map(SalesPayment::getInvoiceId).distinct().toList()).stream()
                .collect(Collectors.toMap(SalesInvoice::getId, Function.identity()));
        return payments.stream().map(p -> new Transfer(p, invoices.get(p.getInvoiceId()), confirmed.get(p.getId()))).toList();
    }
}
