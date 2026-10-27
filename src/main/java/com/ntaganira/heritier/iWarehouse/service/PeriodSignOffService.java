package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.entity.AccountingPeriod;
import com.ntaganira.heritier.iWarehouse.entity.PeriodSignOff;
import com.ntaganira.heritier.iWarehouse.enums.AuthorityLevel;
import com.ntaganira.heritier.iWarehouse.enums.NotificationKind;
import com.ntaganira.heritier.iWarehouse.enums.PeriodStatus;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.exception.NotFoundException;
import com.ntaganira.heritier.iWarehouse.repository.AccountingPeriodRepository;
import com.ntaganira.heritier.iWarehouse.repository.PeriodSignOffRepository;
import com.ntaganira.heritier.iWarehouse.security.AppUserPrincipal;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : PeriodSignOffService.java
 * - Date      : 2026. 10. 15.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : The month's accounts signed off before the MD and the Board (BP 4.2, Annex C; U.15): a closed month is
 *               signed off by the COO, then by the MD, two people, each by their office under the Delegation of
 *               Authority (a higher office may sign for a lower one). A sign-off counts for the month as it was closed:
 *               reopening it leaves the sign-offs on record but they no longer count.
 * </pre>
 */
@Service
@Transactional(readOnly = true)
public class PeriodSignOffService {

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MM/yyyy");

    private final PeriodSignOffRepository repo;
    private final AccountingPeriodRepository periodRepo;
    private final Notifier notifier;
    private final Clock clock;

    public PeriodSignOffService(PeriodSignOffRepository repo, AccountingPeriodRepository periodRepo, Notifier notifier, Clock clock) {
        this.repo = repo;
        this.periodRepo = periodRepo;
        this.notifier = notifier;
        this.clock = clock;
    }

    /** A month's sign-offs, oldest first, and those that count (given since it was last closed). */
    public record SignOffs(List<PeriodSignOff> all, Optional<PeriodSignOff> coo, Optional<PeriodSignOff> md) {

        public boolean isComplete() {
            return coo.isPresent() && md.isPresent();
        }

        public boolean counts(PeriodSignOff s) {
            return coo.map(c -> c.getId().equals(s.getId())).orElse(false) || md.map(m -> m.getId().equals(s.getId())).orElse(false);
        }
    }

    public SignOffs of(AccountingPeriod period) {
        List<PeriodSignOff> all = repo.findByPeriodIdOrderByCreatedAtAsc(period.getId());
        List<PeriodSignOff> current = period.getStatus() == PeriodStatus.CLOSED
                ? all.stream().filter(s -> !s.getCreatedAt().isBefore(period.getClosedAt())).toList() : List.of();
        return new SignOffs(all, current.stream().filter(s -> s.getOffice() == AuthorityLevel.COO).findFirst(),
                current.stream().filter(s -> s.getOffice() == AuthorityLevel.MD).findFirst());
    }

    /** The office the signed-in user would sign off as next on this month: COO first, then MD; empty when not theirs to sign. */
    public Optional<AuthorityLevel> nextFor(AccountingPeriod period) {
        if (period.getStatus() != PeriodStatus.CLOSED) {
            return Optional.empty();
        }
        SignOffs s = of(period);
        AuthorityLevel next = s.coo().isEmpty() ? AuthorityLevel.COO : s.md().isEmpty() ? AuthorityLevel.MD : null;
        Optional<AuthorityLevel> office = office();
        if (next == null || office.isEmpty() || !office.get().atLeast(next)) {
            return Optional.empty();
        }
        Long me = AppUserPrincipal.current().map(AppUserPrincipal::getId).orElse(null);
        if (next == AuthorityLevel.MD && s.coo().map(c -> c.getUserId() != null && c.getUserId().equals(me)).orElse(false)) {
            return Optional.empty();
        }
        return Optional.of(next);
    }

    /** Signs off a closed month at the next office due: the COO first, then the MD (another person). */
    @Transactional
    public PeriodSignOff signOff(UUID periodId, String note) {
        AccountingPeriod period = periodRepo.findById(periodId).orElseThrow(() -> new NotFoundException("AccountingPeriod", periodId));
        if (period.getStatus() != PeriodStatus.CLOSED) {
            throw BusinessException.of("period.signOff.notClosed", period.getPeriodEnd().format(MONTH));
        }
        SignOffs s = of(period);
        if (s.isComplete()) {
            throw BusinessException.of("period.signOff.done", period.getPeriodEnd().format(MONTH));
        }
        AuthorityLevel next = s.coo().isEmpty() ? AuthorityLevel.COO : AuthorityLevel.MD;
        Optional<AuthorityLevel> office = office();
        if (office.isEmpty() || !office.get().atLeast(next)) {
            throw BusinessException.of("period.signOff.office", next.name());
        }
        AppUserPrincipal user = AppUserPrincipal.current().orElseThrow(() -> BusinessException.of("till.noUser"));
        if (next == AuthorityLevel.MD && s.coo().map(c -> user.getId().equals(c.getUserId())).orElse(false)) {
            throw BusinessException.of("period.signOff.samePerson");
        }
        String clean = PartyRules.clean(note);
        if (clean != null && clean.length() > 255) {
            throw BusinessException.onField("note", "period.signOff.noteSize");
        }
        PeriodSignOff signOff = new PeriodSignOff();
        signOff.setPeriodId(period.getId());
        signOff.setOffice(next);
        signOff.setNote(clean);
        signOff.setCreatedAt(LocalDateTime.now(clock));
        signOff.setUserId(user.getId());
        signOff.setUsername(user.getUsername());
        repo.save(signOff);
        if (next == AuthorityLevel.COO) {
            notifier.holders("AUTHORITY_MD", user.getId(), NotificationKind.CLOSE,
                    "notify.period.signOff", "notify.period.signOffText", "/accounting/periods/" + period.getId(),
                    period.getPeriodEnd().format(MONTH), user.getUsername());
        }
        return signOff;
    }

    /** The signed-in user's highest office under the Delegation of Authority. */
    private static Optional<AuthorityLevel> office() {
        return AppUserPrincipal.current().flatMap(u -> AuthorityTiers.highest(u.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority).toList()));
    }
}
