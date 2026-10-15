package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.entity.TransactionTicket;
import com.ntaganira.heritier.iWarehouse.enums.NotificationKind;
import com.ntaganira.heritier.iWarehouse.enums.TicketKind;
import com.ntaganira.heritier.iWarehouse.enums.TicketStatus;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.repository.StockMovementRepository;
import com.ntaganira.heritier.iWarehouse.security.AppUserPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : TicketPostingService.java
 * - Date      : 2026. 10. 15.
 * - User      : Hntaganira
 * - Desc      : Finance posts a countersigned Transaction Ticket (Annex I step 4), not one of its signers. A goods receipt
 *               waiting for its ticket posts now (the stock ledger moves only on its ticket); a movement already made is
 *               confirmed posted. The ticket first, then the receipt's own locks.
 * </pre>
 */
@Service
@Transactional(readOnly = true)
public class TicketPostingService {

    private final TicketService ticketService;
    private final GoodsReceiptService receiptService;
    private final Notifier notifier;
    private final Clock clock;
    private final StockMovementRepository movementRepo;

    public TicketPostingService(TicketService ticketService, GoodsReceiptService receiptService, Notifier notifier, Clock clock,
                                StockMovementRepository movementRepo) {
        this.movementRepo = movementRepo;
        this.ticketService = ticketService;
        this.receiptService = receiptService;
        this.notifier = notifier;
        this.clock = clock;
    }

    /** The result: the ticket, and the receipt posted with it (or null). */
    public record Posted(TransactionTicket ticket, GoodsReceiptService.PostResult receipt) {
    }

    @Transactional
    public Posted post(UUID id) {
        TransactionTicket t = ticketService.lock(id);
        if (t.getStatus() != TicketStatus.COUNTERSIGNED) {
            throw BusinessException.of("ticket.notForPost", t.getNumber());
        }
        if (TicketService.isRaiser(t) || TicketService.isVerifier(t) || TicketService.isCountersigner(t)) {
            throw BusinessException.of("ticket.ownPost", t.getNumber());
        }
        GoodsReceiptService.PostResult receipt = null;
        if (!t.isMovementPosted() && t.getKind() == TicketKind.RECEIPT) {
            receipt = receiptService.postFromTicket(t.getSourceId());
            // Its cost is known once posted at the day's rate: the units it made (the query flushes them first)
            List<Object[]> totals = movementRepo.totalsOf(t.getSourceId(), TicketService.MOVEMENTS.get(TicketKind.RECEIPT));
            if (!totals.isEmpty()) {
                t.setValue(((BigDecimal) totals.get(0)[2]).setScale(2, RoundingMode.HALF_UP));
            }
            t.setMovementPosted(true);
        }
        Optional<AppUserPrincipal> user = AppUserPrincipal.current();
        t.setPostedBy(user.map(AppUserPrincipal::getUsername).orElse("system"));
        t.setPostedById(user.map(AppUserPrincipal::getId).orElse(null));
        t.setPostedAt(LocalDateTime.now(clock));
        t.setStatus(TicketStatus.POSTED);
        notifier.user(t.getRaisedById(), NotificationKind.DECISION, "notify.ticket.posted", "notify.decidedBy",
                "/tickets/" + t.getId(), t.getNumber(), t.getPostedBy());
        return new Posted(t, receipt);
    }
}
