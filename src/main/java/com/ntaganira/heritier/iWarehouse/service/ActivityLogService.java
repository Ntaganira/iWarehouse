package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.audit.AuditContext;
import com.ntaganira.heritier.iWarehouse.entity.ActivityLog;
import com.ntaganira.heritier.iWarehouse.enums.ActivityStatus;
import com.ntaganira.heritier.iWarehouse.repository.ActivityLogRepository;
import com.ntaganira.heritier.iWarehouse.repository.UserRepository;
import com.ntaganira.heritier.iWarehouse.security.AppUserPrincipal;
import jakarta.persistence.criteria.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : ActivityLogService.java
 * - Date      : 2026. 10. 07.
 * - User      : Hntaganira
 * - Desc      : Activity log (SRS 4.13, layer 1). Ported from iVura with two changes:
 *               1) each entry is saved in its OWN transaction (REQUIRES_NEW), so a FAILED
 *                  action is still logged when the business transaction rolls back (AUD-01);
 *               2) entries carry the request id that links them to data_change_logs.
 *               Activity logging stays best-effort: a failure here never breaks the user's action.
 * </pre>
 */
@Service
public class ActivityLogService {

    private static final Logger log = LoggerFactory.getLogger(ActivityLogService.class);
    private static final Set<String> STATUSES = Set.of("SUCCESS", "FAILED");

    private final ActivityLogRepository logRepo;
    private final UserRepository userRepo;
    private final TransactionTemplate newTransaction;

    public ActivityLogService(ActivityLogRepository logRepo, UserRepository userRepo,
                              PlatformTransactionManager transactionManager) {
        this.logRepo = logRepo;
        this.userRepo = userRepo;
        this.newTransaction = new TransactionTemplate(transactionManager);
        this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Records an activity for the current user. */
    public void record(String module, String action, String description, ActivityStatus status) {
        record(AppUserPrincipal.currentUsername(), module, action, description, status);
    }

    /** Records an activity for the given username (used by login handlers before authentication). */
    public void record(String username, String module, String action, String description, ActivityStatus status) {
        AuditContext.Data ctx = AuditContext.current();
        try {
            newTransaction.executeWithoutResult(tx -> {
                Long userId = AppUserPrincipal.current()
                        .filter(p -> p.getUsername().equals(username))
                        .map(AppUserPrincipal::getId)
                        .orElseGet(() -> StringUtils.hasText(username)
                                ? userRepo.findByUsername(username).map(u -> u.getId()).orElse(null)
                                : null);
                logRepo.save(ActivityLog.builder()
                        .requestId(ctx.getRequestId())
                        .userId(userId)
                        .username(truncate(username, 50))
                        .module(module)
                        .action(action)
                        .description(description)
                        .status(status)
                        .ipAddress(ctx.getIpAddress())
                        .userAgent(ctx.getUserAgent())
                        .deviceId(ctx.getDeviceId())
                        .build());
            });
        } catch (RuntimeException e) {
            log.warn("Failed to record activity log [{} / {}]: {}", module, action, e.getMessage());
        }
    }

    public Page<ActivityLog> findPage(Long userId, String module, String action, String status,
                                      LocalDate from, LocalDate to, int page, int size) {
        Specification<ActivityLog> spec = (root, query, cb) -> {
            Predicate p = cb.conjunction();
            if (userId != null) {
                p = cb.and(p, cb.equal(root.get("userId"), userId));
            }
            if (StringUtils.hasText(module)) {
                p = cb.and(p, cb.equal(root.get("module"), module.trim()));
            }
            if (StringUtils.hasText(action)) {
                p = cb.and(p, cb.equal(root.get("action"), action.trim()));
            }
            if (StringUtils.hasText(status) && STATUSES.contains(status.trim().toUpperCase())) {
                p = cb.and(p, cb.equal(root.get("status"), ActivityStatus.valueOf(status.trim().toUpperCase())));
            }
            if (from != null) {
                p = cb.and(p, cb.greaterThanOrEqualTo(root.get("createdAt"), from.atStartOfDay()));
            }
            if (to != null) {
                p = cb.and(p, cb.lessThanOrEqualTo(root.get("createdAt"), to.atTime(LocalTime.MAX)));
            }
            return p;
        };
        return logRepo.findAll(spec, PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")));
    }

    public List<String> findModules() {
        return logRepo.findDistinctModules();
    }

    public List<String> findActions() {
        return logRepo.findDistinctActions();
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
