package com.ntaganira.heritier.iWarehouse.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ntaganira.heritier.iWarehouse.config.Messages;
import com.ntaganira.heritier.iWarehouse.entity.Notification;
import com.ntaganira.heritier.iWarehouse.entity.User;
import com.ntaganira.heritier.iWarehouse.enums.NotificationKind;
import com.ntaganira.heritier.iWarehouse.exception.NotFoundException;
import com.ntaganira.heritier.iWarehouse.repository.NotificationRepository;
import com.ntaganira.heritier.iWarehouse.repository.UserRepository;
import com.ntaganira.heritier.iWarehouse.security.AppUserPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : NotificationService.java
 * - Date      : 2026. 10. 10.
 * - User      : Hntaganira
 * - Desc      : In-app notifications (RPT-06), ported from iVura. Delivers each Notifier.Alert after its transaction
 *               commits, in a transaction of its own: one notification per recipient (the enabled holders of its
 *               permission but the person who caused it, or one user), and an email to those with an address when the
 *               Settings turn email alerts on (AlertMailer). A failed delivery is logged, never thrown back. The header
 *               bell (bell()) and the Notifications page read the signed-in user's own; marking read only sets read_at.
 * </pre>
 */
@Service
@Transactional(readOnly = true)
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);
    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {
    };

    private final NotificationRepository repo;
    private final UserRepository userRepo;
    private final AlertMailer mailer;
    private final Messages messages;
    private final ObjectMapper json;
    private final Clock clock;

    public NotificationService(NotificationRepository repo, UserRepository userRepo, AlertMailer mailer, Messages messages,
                               ObjectMapper json, Clock clock) {
        this.repo = repo;
        this.userRepo = userRepo;
        this.mailer = mailer;
        this.messages = messages;
        this.json = json;
        this.clock = clock;
    }

    /** A notification as shown: its text in the reader's language. */
    public record View(Long id, NotificationKind kind, String title, String message, String link, LocalDateTime createdAt, boolean read) {
    }

    /** What the header bell shows: how many are unread and the latest five. */
    public record Bell(long unread, List<View> latest) {
    }

    // ---------------------------------------------------------------- delivery

    @TransactionalEventListener(fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void deliver(Notifier.Alert alert) {
        try {
            Map<Long, User> recipients = new LinkedHashMap<>();
            if (alert.userId() != null) {
                userRepo.findById(alert.userId()).filter(User::isEnabled).ifPresent(u -> recipients.put(u.getId(), u));
            } else {
                userRepo.findActiveHolding(alert.permission()).forEach(u -> recipients.put(u.getId(), u));
                recipients.remove(alert.exceptUserId());
            }
            if (recipients.isEmpty()) {
                return;
            }
            String args = json.writeValueAsString(alert.args());
            LocalDateTime now = LocalDateTime.now(clock);
            List<String> emails = new ArrayList<>();
            for (User u : recipients.values()) {
                Notification n = new Notification();
                n.setUserId(u.getId());
                n.setKind(alert.kind());
                n.setTitleKey(alert.titleKey());
                n.setMessageKey(alert.messageKey());
                n.setArgs(args);
                n.setLink(alert.link());
                n.setCreatedAt(now);
                repo.save(n);
                if (StringUtils.hasText(u.getEmail())) {
                    emails.add(u.getEmail());
                }
            }
            if (!emails.isEmpty() && mailer.enabled()) {
                Object[] a = alert.args().toArray();
                mailer.send(emails, messages.get(alert.titleKey(), a),
                        alert.messageKey() == null ? "" : messages.get(alert.messageKey(), a), alert.link());
            }
        } catch (JsonProcessingException | RuntimeException e) {
            log.warn("Could not deliver the alert {} ({}): {}", alert.titleKey(), alert.kind(), e.getMessage());
        }
    }

    // ---------------------------------------------------------------- reading

    /** The signed-in user's bell; nothing when nobody is signed in. */
    public Bell bell() {
        Long userId = currentUserId();
        if (userId == null) {
            return new Bell(0, List.of());
        }
        return new Bell(repo.countByUserIdAndReadAtIsNull(userId), repo.findTop5ByUserIdOrderByCreatedAtDescIdDesc(userId).stream().map(this::view).toList());
    }

    public Page<View> page(boolean unreadOnly, int page, int size) {
        Long userId = requireUser();
        PageRequest pageable = PageRequest.of(page, size);
        return (unreadOnly ? repo.findByUserIdAndReadAtIsNullOrderByCreatedAtDescIdDesc(userId, pageable)
                : repo.findByUserIdOrderByCreatedAtDescIdDesc(userId, pageable)).map(this::view);
    }

    public long unreadCount() {
        Long userId = currentUserId();
        return userId == null ? 0 : repo.countByUserIdAndReadAtIsNull(userId);
    }

    /** Marks one of the user's own notifications read and gives the page it points to (null when none). */
    @Transactional
    public String open(Long id) {
        Notification n = repo.findByIdAndUserId(id, requireUser()).orElseThrow(() -> new NotFoundException("Notification", id));
        if (n.getReadAt() == null) {
            n.setReadAt(LocalDateTime.now(clock));
        }
        return n.getLink();
    }

    /** Marks all the user's unread notifications read; how many. */
    @Transactional
    public int readAll() {
        LocalDateTime now = LocalDateTime.now(clock);
        List<Notification> unread = repo.findByUserIdAndReadAtIsNull(requireUser());
        unread.forEach(n -> n.setReadAt(now));
        return unread.size();
    }

    private View view(Notification n) {
        Object[] args = args(n.getArgs());
        return new View(n.getId(), n.getKind(), messages.get(n.getTitleKey(), args),
                n.getMessageKey() == null ? null : messages.get(n.getMessageKey(), args), n.getLink(), n.getCreatedAt(), n.isRead());
    }

    private Object[] args(String text) {
        if (!StringUtils.hasText(text)) {
            return new Object[0];
        }
        try {
            return json.readValue(text, STRINGS).toArray();
        } catch (JsonProcessingException e) {
            return new Object[0];
        }
    }

    private static Long currentUserId() {
        return AppUserPrincipal.current().map(AppUserPrincipal::getId).orElse(null);
    }

    private static Long requireUser() {
        Long id = currentUserId();
        if (id == null) {
            throw new NotFoundException("User", "current");
        }
        return id;
    }
}
