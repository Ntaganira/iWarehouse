package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.entity.AccessReview;
import com.ntaganira.heritier.iWarehouse.entity.Permission;
import com.ntaganira.heritier.iWarehouse.entity.Role;
import com.ntaganira.heritier.iWarehouse.entity.User;
import com.ntaganira.heritier.iWarehouse.enums.ActivityStatus;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.repository.AccessReviewRepository;
import com.ntaganira.heritier.iWarehouse.repository.ActivityLogRepository;
import com.ntaganira.heritier.iWarehouse.repository.UserRepository;
import com.ntaganira.heritier.iWarehouse.security.AppUserPrincipal;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : AccessReviewService.java
 * - Date      : 2026. 10. 15.
 * - User      : Hntaganira
 * - Desc      : The quarterly access review (Board Paper Table 8, Table 9): every user with their active roles, how many
 *               rights those give and when they last signed in, so whoever reviews (PERM_REVIEW_ACCESS) can check that
 *               rights match the segregation matrix and that nobody who left still has access; the review itself is then
 *               recorded with what was checked and changed. A review is due a quarter after the last.
 * </pre>
 */
@Service
@Transactional(readOnly = true)
public class AccessReviewService {

    /** A review is due this many days after the last one (a quarter). */
    public static final int EVERY_DAYS = 92;

    private final AccessReviewRepository repo;
    private final UserRepository userRepo;
    private final ActivityLogRepository activityRepo;
    private final Clock clock;

    public AccessReviewService(AccessReviewRepository repo, UserRepository userRepo, ActivityLogRepository activityRepo, Clock clock) {
        this.repo = repo;
        this.userRepo = userRepo;
        this.activityRepo = activityRepo;
        this.clock = clock;
    }

    /** A user as the review shows them: their active roles, the rights those give, when they last signed in. */
    public record UserAccess(User user, List<String> roles, int rights, LocalDateTime lastSignIn) {
    }

    /** Every user, by name. */
    public List<UserAccess> users() {
        Map<String, LocalDateTime> lastSignIn = new HashMap<>();
        for (Object[] row : activityRepo.lastOf("LOGIN", ActivityStatus.SUCCESS)) {
            lastSignIn.put((String) row[0], (LocalDateTime) row[1]);
        }
        List<UserAccess> users = new ArrayList<>();
        for (User u : userRepo.findAllWithRights()) {
            List<Role> active = u.getRoles().stream().filter(Role::isEnabled).sorted(Comparator.comparing(Role::getCode)).toList();
            Set<String> rights = new HashSet<>();
            active.forEach(r -> r.getPermissions().stream().map(Permission::getCode).forEach(rights::add));
            users.add(new UserAccess(u, active.stream().map(Role::getCode).toList(), rights.size(), lastSignIn.get(u.getUsername())));
        }
        return users;
    }

    public Optional<AccessReview> latest() {
        return repo.findFirstByOrderByReviewedAtDesc();
    }

    /** Whether a review is due: none yet, or the last one a quarter ago or more. */
    public boolean due() {
        return latest().map(r -> ChronoUnit.DAYS.between(r.getReviewedAt().toLocalDate(), LocalDateTime.now(clock).toLocalDate()) >= EVERY_DAYS)
                .orElse(true);
    }

    public LocalDate today() {
        return LocalDate.now(clock);
    }

    public Page<AccessReview> reviews(int page, int size) {
        return repo.findAllByOrderByReviewedAtDesc(PageRequest.of(page, size));
    }

    /** Records a review: what was checked and what was changed, with today's counts. */
    @Transactional
    public AccessReview record(String note) {
        String text = PartyRules.clean(note);
        if (text == null) {
            throw BusinessException.onField("note", "accessReview.note.required");
        }
        if (text.length() > 500) {
            throw BusinessException.onField("note", "accessReview.note.size");
        }
        List<User> users = userRepo.findAll();
        AccessReview review = new AccessReview();
        review.setReviewedAt(LocalDateTime.now(clock));
        review.setReviewedBy(AppUserPrincipal.currentUsername());
        review.setUsersCount(users.size());
        review.setEnabledCount((int) users.stream().filter(User::isEnabled).count());
        review.setNote(text);
        return repo.save(review);
    }
}
