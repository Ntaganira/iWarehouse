package com.ntaganira.heritier.iWarehouse.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ntaganira.heritier.iWarehouse.config.Messages;
import com.ntaganira.heritier.iWarehouse.config.NumberFormats;
import com.ntaganira.heritier.iWarehouse.entity.AlertState;
import com.ntaganira.heritier.iWarehouse.entity.Notification;
import com.ntaganira.heritier.iWarehouse.entity.Product;
import com.ntaganira.heritier.iWarehouse.entity.User;
import com.ntaganira.heritier.iWarehouse.enums.NotificationKind;
import com.ntaganira.heritier.iWarehouse.repository.AlertStateRepository;
import com.ntaganira.heritier.iWarehouse.repository.NotificationRepository;
import com.ntaganira.heritier.iWarehouse.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Notifications and alerts (RPT-06): an alert reaches the holders of its permission but the person who caused it (or one
 * user), with an email to those with an address when email alerts are on; low stock is told once until it clears.
 */
class NotificationServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-10T08:00:00Z"), ZoneId.of("Africa/Kigali"));

    private final NotificationRepository repo = mock(NotificationRepository.class);
    private final UserRepository userRepo = mock(UserRepository.class);
    private final AlertMailer mailer = mock(AlertMailer.class);
    private final Messages messages = mock(Messages.class);
    private final NotificationService service = new NotificationService(repo, userRepo, mailer, messages, new ObjectMapper(), CLOCK);

    @Test
    void anAlertReachesTheHoldersButWhoCausedItAndEmailsThoseWithAnAddress() {
        when(userRepo.findActiveHolding("APPROVE_MANUAL_JOURNAL")).thenReturn(List.of(user(1L, "owner@glass.rw"), user(2L, null), user(3L, "acc@glass.rw")));
        when(mailer.enabled()).thenReturn(true);
        when(messages.get(eq("notify.manualJournal.waiting"), any(Object[].class))).thenReturn("Manual journal MJ-1 waits for your approval");
        when(messages.get(eq("notify.manualJournal.waitingText"), any(Object[].class))).thenReturn("Asked by acc: rent");

        service.deliver(new Notifier.Alert(NotificationKind.APPROVAL, "APPROVE_MANUAL_JOURNAL", null, 3L, "notify.manualJournal.waiting",
                "notify.manualJournal.waitingText", List.of("MJ-1", "acc", "rent"), "/accounting/manual-journals/1"));

        ArgumentCaptor<Notification> saved = ArgumentCaptor.forClass(Notification.class);
        verify(repo, times(2)).save(saved.capture());
        assertThat(saved.getAllValues()).extracting(Notification::getUserId).containsExactly(1L, 2L);     // not 3, who asked
        Notification n = saved.getAllValues().get(0);
        assertThat(n.getKind()).isEqualTo(NotificationKind.APPROVAL);
        assertThat(n.getArgs()).isEqualTo("[\"MJ-1\",\"acc\",\"rent\"]");
        assertThat(n.getLink()).isEqualTo("/accounting/manual-journals/1");
        assertThat(n.isRead()).isFalse();
        verify(mailer).send(List.of("owner@glass.rw"), "Manual journal MJ-1 waits for your approval", "Asked by acc: rent", "/accounting/manual-journals/1");
    }

    @Test
    void noEmailWhenTheyAreOffAndNothingForAMissingOrDisabledUser() {
        when(userRepo.findById(5L)).thenReturn(Optional.of(user(5L, "cashier@glass.rw")));
        User disabled = user(6L, "gone@glass.rw");
        disabled.setEnabled(false);
        when(userRepo.findById(6L)).thenReturn(Optional.of(disabled));
        when(mailer.enabled()).thenReturn(false);

        service.deliver(new Notifier.Alert(NotificationKind.DECISION, null, 5L, null, "notify.adjustment.approved", "notify.decidedBy",
                List.of("ADJ-1", "owner"), "/stock-adjustments/1"));
        service.deliver(new Notifier.Alert(NotificationKind.DECISION, null, 6L, null, "notify.adjustment.approved", null, List.of(), null));
        service.deliver(new Notifier.Alert(NotificationKind.DECISION, null, 7L, null, "notify.adjustment.approved", null, List.of(), null));

        verify(repo, times(1)).save(any(Notification.class));
        verify(mailer, never()).send(anyList(), anyString(), anyString(), any());
    }

    @Test
    void theNotifierPublishesAnAlertAndToNobodyWithoutAUser() {
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        Notifier notifier = new Notifier(events);
        notifier.holders("APPROVE_SALE", 4L, NotificationKind.APPROVAL, "t", "m", "/sale-approvals/1", "APR-1", null);
        notifier.user(null, NotificationKind.DECISION, "t", null, null);

        ArgumentCaptor<Notifier.Alert> alert = ArgumentCaptor.forClass(Notifier.Alert.class);
        verify(events, times(1)).publishEvent(alert.capture());
        assertThat(alert.getValue().permission()).isEqualTo("APPROVE_SALE");
        assertThat(alert.getValue().exceptUserId()).isEqualTo(4L);
        assertThat(alert.getValue().args()).containsExactly("APR-1", "");       // a missing value is blank
    }

    @Test
    void lowStockIsToldOnceUntilTheGlassIsBackAbove() {
        StockSummaryService summary = mock(StockSummaryService.class);
        Notifier notifier = mock(Notifier.class);
        Map<String, AlertState> states = new HashMap<>();
        AlertStateRepository stateRepo = mock(AlertStateRepository.class);
        when(stateRepo.findByKeyStartingWithAndClearedAtIsNull("LOW_STOCK:")).thenAnswer(i -> states.values().stream().filter(AlertState::isActive).toList());
        when(stateRepo.findById(anyString())).thenAnswer(i -> Optional.ofNullable(states.get(i.<String>getArgument(0))));
        when(stateRepo.save(any(AlertState.class))).thenAnswer(i -> {
            AlertState s = i.getArgument(0);
            states.put(s.getKey(), s);
            return s;
        });
        StockAlertService alerts = new StockAlertService(summary, stateRepo, notifier, new NumberFormats(), CLOCK);
        Product clear4 = new Product();
        clear4.setId(UUID.randomUUID());
        clear4.setCode("CLR-4");
        StockSummary.Reorder low = new StockSummary.Reorder(clear4, new BigDecimal("2.0000"), new BigDecimal("20"));

        when(summary.reorder()).thenReturn(List.of(low));
        assertThat(alerts.checkLowStock()).isEqualTo(1);
        assertThat(alerts.checkLowStock()).isZero();                                   // still low: told once
        verify(notifier, times(1)).holders(eq("ALERT_LOW_STOCK"), isNull(), eq(NotificationKind.LOW_STOCK), eq("notify.lowStock.title"),
                eq("notify.lowStock.message"), eq("/stock/summary"), eq("CLR-4"), eq("2"), eq("20"));

        when(summary.reorder()).thenReturn(List.of());
        assertThat(alerts.checkLowStock()).isZero();
        assertThat(states.get("LOW_STOCK:" + clear4.getId()).isActive()).isFalse();   // back above: cleared

        when(summary.reorder()).thenReturn(List.of(low));
        assertThat(alerts.checkLowStock()).isEqualTo(1);                               // low again: told again
        assertThat(states.get("LOW_STOCK:" + clear4.getId()).isActive()).isTrue();
    }

    private static User user(long id, String email) {
        User u = new User();
        u.setId(id);
        u.setUsername("user" + id);
        u.setEmail(email);
        u.setEnabled(true);
        return u;
    }
}
