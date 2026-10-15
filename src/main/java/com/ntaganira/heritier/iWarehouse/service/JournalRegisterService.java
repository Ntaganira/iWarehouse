package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.entity.JournalEntry;
import com.ntaganira.heritier.iWarehouse.entity.JournalRegisterCertification;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.exception.NotFoundException;
import com.ntaganira.heritier.iWarehouse.repository.JournalEntryRepository;
import com.ntaganira.heritier.iWarehouse.repository.JournalRegisterCertificationRepository;
import com.ntaganira.heritier.iWarehouse.security.AppUserPrincipal;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : JournalRegisterService.java
 * - Date      : 2026. 10. 15.
 * - User      : Hntaganira
 * - Desc      : The register of journal numbers (F-16, U.07): a month's journals by number with their total and any
 *               gap in the numbering (JournalRegister); once the month is closed the Director of Finance certifies it,
 *               once, and the Internal Controller records the sample checked (another person).
 * </pre>
 */
@Service
@Transactional(readOnly = true)
public class JournalRegisterService {

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MM/yyyy");

    private final JournalEntryRepository entryRepo;
    private final JournalRegisterCertificationRepository repo;
    private final PeriodLock periodLock;
    private final Clock clock;

    public JournalRegisterService(JournalEntryRepository entryRepo, JournalRegisterCertificationRepository repo, PeriodLock periodLock,
                                  Clock clock) {
        this.entryRepo = entryRepo;
        this.repo = repo;
        this.periodLock = periodLock;
        this.clock = clock;
    }

    /** A month's register: its journals by number, their total, the gaps, whether the month is closed, its certification. */
    public record Register(YearMonth month, List<JournalEntry> journals, BigDecimal total, List<String> gaps, boolean closed,
                           JournalRegisterCertification certification) {

        public String getFirstNumber() {
            return journals.isEmpty() ? null : journals.get(0).getNumber();
        }

        public String getLastNumber() {
            return journals.isEmpty() ? null : journals.get(journals.size() - 1).getNumber();
        }
    }

    public Register register(YearMonth month) {
        List<JournalEntry> journals = entryRepo.findByEntryDateBetweenOrderByNumberAsc(month.atDay(1), month.atEndOfMonth());
        BigDecimal total = journals.stream().map(JournalEntry::getTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
        List<String> numbers = journals.stream().map(JournalEntry::getNumber).toList();
        Set<String> series = new HashSet<>();
        numbers.forEach(n -> JournalRegister.parse(n).ifPresent(p -> series.add(p.series())));
        List<String> all = series.stream().flatMap(s -> entryRepo.numbersOfSeries(s).stream()).toList();
        return new Register(month, journals, total, JournalRegister.gaps(numbers, all), periodLock.isClosed(month.atEndOfMonth()),
                repo.findByPeriodEnd(month.atEndOfMonth()).orElse(null));
    }

    public Page<JournalRegisterCertification> certifications(int page, int size) {
        return repo.findAllBy(PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "periodEnd")));
    }

    public Optional<JournalRegisterCertification> certification(YearMonth month) {
        return repo.findByPeriodEnd(month.atEndOfMonth());
    }

    public YearMonth lastMonth() {
        return YearMonth.now(clock).minusMonths(1);
    }

    /** Certifies a closed month's register as it stands (the Director of Finance): once. */
    @Transactional
    public JournalRegisterCertification certify(YearMonth month, String note) {
        Register register = register(month);
        if (!register.closed()) {
            throw BusinessException.of("journalRegister.notClosed", month.format(MONTH));
        }
        if (register.certification() != null) {
            throw BusinessException.of("journalRegister.certified", month.format(MONTH), register.certification().getCertifiedBy());
        }
        String clean = PartyRules.clean(note);
        if (clean != null && clean.length() > 255) {
            throw BusinessException.onField("certifyNote", "journalRegister.note.size");
        }
        if (!register.gaps().isEmpty() && clean == null) {
            throw BusinessException.onField("certifyNote", "journalRegister.gapNote");
        }
        AppUserPrincipal user = AppUserPrincipal.current().orElseThrow(() -> BusinessException.of("till.noUser"));
        JournalRegisterCertification c = new JournalRegisterCertification();
        c.setPeriodEnd(month.atEndOfMonth());
        c.setJournals(register.journals().size());
        c.setFirstNumber(register.getFirstNumber());
        c.setLastNumber(register.getLastNumber());
        c.setGaps(JournalRegister.summary(register.gaps(), 500));
        c.setTotal(register.total());
        c.setCertifiedBy(user.getUsername());
        c.setCertifiedById(user.getId());
        c.setCertifiedAt(LocalDateTime.now(clock));
        c.setCertifyNote(clean);
        return repo.save(c);
    }

    /** Records the Internal Controller's sample of a certified register: what was checked; once, never by who certified it. */
    @Transactional
    public JournalRegisterCertification sample(UUID id, String note) {
        JournalRegisterCertification c = repo.lockById(id).orElseThrow(() -> new NotFoundException("JournalRegisterCertification", id));
        if (c.isSampled()) {
            throw BusinessException.of("journalRegister.sampled", YearMonth.from(c.getPeriodEnd()).format(MONTH), c.getSampledBy());
        }
        AppUserPrincipal user = AppUserPrincipal.current().orElseThrow(() -> BusinessException.of("till.noUser"));
        if (user.getId().equals(c.getCertifiedById())) {
            throw BusinessException.of("journalRegister.ownSample");
        }
        String clean = PartyRules.clean(note);
        if (clean == null || clean.length() > 500) {
            throw BusinessException.onField("sampleNote", "journalRegister.sampleNote.required");
        }
        c.setSampleNote(clean);
        c.setSampledBy(user.getUsername());
        c.setSampledById(user.getId());
        c.setSampledAt(LocalDateTime.now(clock));
        return c;
    }
}
