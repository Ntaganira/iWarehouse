package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.entity.TransactionTicket;
import com.ntaganira.heritier.iWarehouse.enums.*;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.repository.StockMovementRepository;
import com.ntaganira.heritier.iWarehouse.repository.TransactionTicketRepository;
import com.ntaganira.heritier.iWarehouse.security.AppUserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Transaction Tickets (BP 7.4, Annex I, decision D6): raised once per movement document by whoever moved the glass, then
 * verified, countersigned and posted by three other people, each step in turn; a goods receipt waiting for its ticket
 * posts when Finance posts it.
 */
class TicketServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-15T08:00:00Z"), ZoneId.of("Africa/Kigali"));
    private static final LocalDateTime NOW = LocalDateTime.now(CLOCK);

    private final List<TransactionTicket> saved = new ArrayList<>();
    private TransactionTicketRepository repo;
    private StockMovementRepository movements;
    private SettingService settings;
    private Notifier notifier;
    private GoodsReceiptService receipts;
    private TicketService service;
    private TicketPostingService posting;

    @BeforeEach
    void setUp() {
        repo = mock(TransactionTicketRepository.class);
        when(repo.save(any())).thenAnswer(a -> {
            TransactionTicket t = a.getArgument(0);
            t.setId(UUID.randomUUID());
            saved.add(t);
            when(repo.lockById(t.getId())).thenReturn(Optional.of(t));
            return t;
        });
        movements = mock(StockMovementRepository.class);
        DocumentNumberService numbers = mock(DocumentNumberService.class);
        when(numbers.next(DocumentType.TRANSACTION_TICKET)).thenReturn("TT-WH-2026-000001", "TT-WH-2026-000002", "TT-WH-2026-000003");
        settings = mock(SettingService.class);
        when(settings.getBoolean(SettingKey.TICKET_ENABLED)).thenReturn(true);
        when(settings.getBoolean(SettingKey.TICKET_GATE_RECEIPTS)).thenReturn(true);
        when(settings.get(SettingKey.TICKET_SINCE)).thenReturn("2026-10-01");
        notifier = mock(Notifier.class);
        service = new TicketService(repo, movements, numbers, settings, notifier, CLOCK);
        receipts = mock(GoodsReceiptService.class);
        posting = new TicketPostingService(service, receipts, notifier, CLOCK, movements);
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    // ---------------------------------------------------------------- raising

    @Test
    void eachMovementDocumentGetsOneTicketRaisedByWhoMovedTheGlass() {
        UUID transfer = UUID.randomUUID();
        UUID adjustment = UUID.randomUUID();
        UUID trip = UUID.randomUUID();
        when(movements.documentsSince(eq(LocalDateTime.parse("2026-10-01T00:00")), any())).thenReturn(List.of(
                row("TRANSFER", transfer, "TRF-WH-2026-000004", MovementType.TRANSFER, "store1", 5L),
                row("ADJUSTMENT", adjustment, "ADJ-WH-2026-000002", MovementType.LOSS, "store2", 6L),
                row("ADJUSTMENT", adjustment, "ADJ-WH-2026-000002", MovementType.ADJUSTMENT, "store2", 6L),   // same ticket
                row("TRIP", trip, "TRP-WH-2026-000003", MovementType.LOAD, "dispatch1", 7L)));
        when(repo.existsBySourceTypeAndSourceIdAndKind("TRIP", trip, TicketKind.LOAD)).thenReturn(true);                // raised before
        when(movements.totalsOf(eq(transfer), any())).thenReturn(List.<Object[]>of(new Object[]{2L, new BigDecimal("14.445"), new BigDecimal("389000.5")}));
        when(movements.totalsOf(eq(adjustment), any())).thenReturn(List.of());

        assertThat(service.raiseDue()).isEqualTo(2);
        assertThat(saved).extracting(TransactionTicket::getKind).containsExactly(TicketKind.TRANSFER, TicketKind.ADJUSTMENT);
        TransactionTicket t = saved.get(0);
        assertThat(t.getSourceNumber()).isEqualTo("TRF-WH-2026-000004");
        assertThat(t.getPieces()).isEqualTo(2);
        assertThat(t.getAreaM2()).isEqualByComparingTo("14.4450");
        assertThat(t.getValue()).isEqualByComparingTo("389000.50");
        assertThat(t.getRaisedBy()).isEqualTo("store1");
        assertThat(t.getRaisedById()).isEqualTo(5L);
        assertThat(t.isMovementPosted()).isTrue();
        assertThat(t.getStatus()).isEqualTo(TicketStatus.RAISED);
        verify(notifier).holders("VERIFY_TICKET", null, NotificationKind.APPROVAL, "notify.ticket.raised", "notify.ticket.raisedText",
                "/tickets?status=RAISED", "2");
    }

    @Test
    void salesAndCutsGetNoTicketAndNothingIsRaisedWhenTicketsAreOff() {
        assertThat(TicketService.kindOf(MovementType.SALE)).isNull();
        assertThat(TicketService.kindOf(MovementType.CUTTING_OUTPUT)).isNull();
        assertThat(TicketService.kindOf(MovementType.DISPATCH)).isEqualTo(TicketKind.SITE_DISPATCH);
        assertThat(TicketService.kindOf(MovementType.BREAKAGE)).isEqualTo(TicketKind.RETURN);
        when(settings.getBoolean(SettingKey.TICKET_ENABLED)).thenReturn(false);
        assertThat(service.raiseDue()).isZero();
        verifyNoInteractions(movements);
    }

    @Test
    void aReceiptTicketWaitsForItsMovementAndIsRaisedAgainOnlyOnceRejected() {
        signIn(5L, "store1");
        UUID receipt = UUID.randomUUID();
        when(repo.findBySourceTypeAndSourceIdAndKind(TicketService.SOURCE_GOODS_RECEIPT, receipt, TicketKind.RECEIPT)).thenReturn(Optional.empty());
        TransactionTicket t = service.raiseReceipt(receipt, "GRN-WH-2026-000003", 28, new BigDecimal("202.23"));
        assertThat(t.isMovementPosted()).isFalse();
        assertThat(t.getPieces()).isEqualTo(28);
        assertThat(t.getRaisedBy()).isEqualTo("store1");

        when(repo.findBySourceTypeAndSourceIdAndKind(TicketService.SOURCE_GOODS_RECEIPT, receipt, TicketKind.RECEIPT)).thenReturn(Optional.of(t));
        assertThat(service.raiseReceipt(receipt, "GRN-WH-2026-000003", 28, new BigDecimal("202.23"))).isSameAs(t);
        assertThat(saved).hasSize(1);

        t.setStatus(TicketStatus.REJECTED);
        t.setRejectReason("count wrong");
        service.raiseReceipt(receipt, "GRN-WH-2026-000003", 27, new BigDecimal("195.0075"));
        assertThat(t.getStatus()).isEqualTo(TicketStatus.RAISED);
        assertThat(t.getRejectReason()).isNull();
        assertThat(saved).hasSize(1);
    }

    // ---------------------------------------------------------------- signing

    @Test
    void threeOtherPeopleSignInTurn() {
        TransactionTicket t = raised(5L);
        assertKey(() -> service.verify(t.getId(), null), 5L, "ticket.ownVerify");
        assertKey(() -> service.countersign(t.getId(), null), 6L, "ticket.notForCountersign");
        signIn(6L, "supply1");
        service.verify(t.getId(), "count matches");
        assertThat(t.getStatus()).isEqualTo(TicketStatus.VERIFIED);
        assertThat(t.getVerifyNote()).isEqualTo("count matches");

        assertKey(() -> service.countersign(t.getId(), null), 6L, "ticket.ownCountersign");
        signIn(7L, "commercial1");
        service.countersign(t.getId(), null);
        assertThat(t.getStatus()).isEqualTo(TicketStatus.COUNTERSIGNED);
        verify(notifier).holders("POST_TICKET", 7L, NotificationKind.APPROVAL, "notify.ticket.toPost", "notify.ticket.toPostText",
                "/tickets/" + t.getId(), t.getNumber(), t.getSourceNumber());

        assertKey(() -> posting.post(t.getId()), 7L, "ticket.ownPost");
        assertKey(() -> posting.post(t.getId()), 5L, "ticket.ownPost");
        signIn(8L, "finance1");
        posting.post(t.getId());
        assertThat(t.getStatus()).isEqualTo(TicketStatus.POSTED);
        assertThat(t.getPostedBy()).isEqualTo("finance1");
        verifyNoInteractions(receipts);
    }

    @Test
    void postingAReceiptTicketPostsTheReceiptAndTakesItsCost() {
        TransactionTicket t = raised(5L);
        t.setKind(TicketKind.RECEIPT);
        t.setMovementPosted(false);
        t.setStatus(TicketStatus.COUNTERSIGNED);
        t.setVerifiedById(6L);
        t.setCountersignedById(7L);
        when(movements.totalsOf(eq(t.getSourceId()), any())).thenReturn(List.<Object[]>of(new Object[]{28L, new BigDecimal("202.23"), new BigDecimal("5123456.789")}));
        signIn(8L, "finance1");
        posting.post(t.getId());
        verify(receipts).postFromTicket(t.getSourceId());
        assertThat(t.isMovementPosted()).isTrue();
        assertThat(t.getValue()).isEqualByComparingTo("5123456.79");
        assertThat(t.getStatus()).isEqualTo(TicketStatus.POSTED);
    }

    @Test
    void aSignerRejectsWithAReasonNeverWhoRaisedIt() {
        TransactionTicket t = raised(5L);
        assertKey(() -> service.reject(t.getId(), "wrong rack"), 5L, "ticket.ownReject");
        assertKey(() -> service.reject(t.getId(), " "), 6L, "po.reason.required");
        signIn(6L, "supply1");
        service.reject(t.getId(), "wrong rack");
        assertThat(t.getStatus()).isEqualTo(TicketStatus.REJECTED);
        assertThat(t.getRejectedBy()).isEqualTo("supply1");
        verify(notifier).user(5L, NotificationKind.DECISION, "notify.ticket.rejected", "notify.decidedBy", "/tickets/" + t.getId(),
                t.getNumber(), "supply1");
        assertKey(() -> service.verify(t.getId(), null), 6L, "ticket.notForVerify");
    }

    // ---------------------------------------------------------------- helpers

    private TransactionTicket raised(long raiserId) {
        TransactionTicket t = new TransactionTicket();
        t.setNumber("TT-WH-2026-000009");
        t.setKind(TicketKind.TRANSFER);
        t.setSourceType("TRANSFER");
        t.setSourceId(UUID.randomUUID());
        t.setSourceNumber("TRF-WH-2026-000004");
        t.setPieces(2);
        t.setAreaM2(new BigDecimal("14.4450"));
        t.setValue(new BigDecimal("389000.50"));
        t.setMovementPosted(true);
        t.setStatus(TicketStatus.RAISED);
        t.setRaisedBy("store1");
        t.setRaisedById(raiserId);
        t.setRaisedAt(NOW);
        repo.save(t);
        return t;
    }

    private static Object[] row(String refType, UUID refId, String number, MovementType type, String username, Long userId) {
        return new Object[]{refType, refId, number, type, NOW.minusHours(1), username, userId};
    }

    private void assertKey(Runnable call, long userId, String key) {
        signIn(userId, "user" + userId);
        assertThatThrownBy(call::run).isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getMessageKey()).isEqualTo(key));
    }

    private static void signIn(long id, String username) {
        AppUserPrincipal principal = new AppUserPrincipal(id, username, username, "x", true, true, List.of());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }
}
