package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.dto.ExchangeRateDto;
import com.ntaganira.heritier.iWarehouse.entity.Currency;
import com.ntaganira.heritier.iWarehouse.entity.ExchangeRate;
import com.ntaganira.heritier.iWarehouse.enums.RateSource;
import com.ntaganira.heritier.iWarehouse.enums.SettingKey;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.exception.NotFoundException;
import com.ntaganira.heritier.iWarehouse.repository.CurrencyRepository;
import com.ntaganira.heritier.iWarehouse.repository.ExchangeRateRepository;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : ExchangeRateService.java
 * - Date      : 2026. 10. 07.
 * - User      : Hntaganira
 * - Desc      : Exchange rates by date and source (ACC-02), and the rate a document must use.
 *               rateFor() takes the latest rate on or before the document date and refuses when there
 *               is none or it is older than the Settings limit: no document runs on a guessed rate.
 *               A rate more than 10% away from the previous one needs confirming (typing slips).
 *               Rates are corrected with a reason, never deleted.
 * </pre>
 */
@Service
@Transactional(readOnly = true)
public class ExchangeRateService {

    /** A new rate further than this from the previous one (percent) needs an explicit confirmation. */
    public static final BigDecimal LARGE_CHANGE_PERCENT = BigDecimal.TEN;
    public static final int MAX_IMPORT_LINES = 2000;

    private final ExchangeRateRepository repo;
    private final CurrencyRepository currencyRepo;
    private final SettingService settingService;
    private final Clock clock;

    public ExchangeRateService(ExchangeRateRepository repo, CurrencyRepository currencyRepo,
                               SettingService settingService, Clock clock) {
        this.repo = repo;
        this.currencyRepo = currencyRepo;
        this.settingService = settingService;
        this.clock = clock;
    }

    /** The rate a document uses; it stores all four values. For RWF the rate is 1 and the source null. */
    public record AppliedRate(String currencyCode, BigDecimal rate, LocalDate rateDate, RateSource source) {

        /** Amount in RWF, unrounded: the document rounds its total. */
        public BigDecimal toBase(BigDecimal amount) {
            return CurrencyMath.toBase(amount, rate);
        }
    }

    /** Latest rate of an active currency (null if none yet), its age in days and change from the one before. */
    public record LatestRate(Currency currency, ExchangeRate rate, Long ageDays, boolean stale, BigDecimal changePercent) {
    }

    /** A correction: the rate after it, the rate before it, and whether anything changed. */
    public record Correction(ExchangeRate rate, BigDecimal oldRate, boolean changed) {
    }

    /** Result of a file import. When problems is not empty nothing was saved. */
    public record ImportResult(int added, int unchanged, List<RateCsv.Problem> problems) {

        public boolean ok() {
            return problems.isEmpty();
        }
    }

    // ---------------------------------------------------------------- rates for documents

    /** Rate source used when a document does not ask for one (Settings). */
    public RateSource defaultSource() {
        try {
            return RateSource.valueOf(settingService.get(SettingKey.DEFAULT_RATE_SOURCE));
        } catch (IllegalArgumentException | NullPointerException e) {
            return RateSource.BNR;
        }
    }

    public int maxAgeDays() {
        return settingService.getInt(SettingKey.MAX_RATE_AGE_DAYS);
    }

    public LocalDate today() {
        return LocalDate.now(clock);
    }

    @Transactional(readOnly = true, noRollbackFor = BusinessException.class)
    public AppliedRate rateFor(String currencyCode, LocalDate date) {
        return rateFor(currencyCode, date, defaultSource());
    }

    /**
     * Rate for a document dated {@code date}: the latest rate of that source on or before the date.
     * Refused when the currency is unknown or inactive, when there is no rate yet, or when the latest
     * one is older than the Settings limit. A refusal does not mark the caller's transaction for rollback,
     * so a page can show it (e.g. a draft bill whose rate is missing) and still read on.
     */
    @Transactional(readOnly = true, noRollbackFor = BusinessException.class)
    public AppliedRate rateFor(String currencyCode, LocalDate date, RateSource source) {
        Currency currency = currencyRepo.findByCode(currencyCode).filter(Currency::isEnabled)
                .orElseThrow(() -> BusinessException.of("rate.currency.unknown", currencyCode));
        if (currency.isBaseCurrency()) {
            return new AppliedRate(currency.getCode(), BigDecimal.ONE, date, null);
        }
        ExchangeRate rate = repo.findFirstByCurrencyCodeAndSourceAndRateDateLessThanEqualOrderByRateDateDesc(
                        currency.getCode(), source, date)
                .orElseThrow(() -> BusinessException.of("rate.missing", currency.getCode(), source.name(), date));
        long age = ChronoUnit.DAYS.between(rate.getRateDate(), date);
        int maxAge = maxAgeDays();
        if (age > maxAge) {
            throw BusinessException.of("rate.stale", currency.getCode(), source.name(), rate.getRateDate(), age, maxAge);
        }
        return new AppliedRate(currency.getCode(), rate.getRate(), rate.getRateDate(), source);
    }

    // ---------------------------------------------------------------- screens

    public Page<ExchangeRate> findPage(String currencyCode, RateSource source, LocalDate from, LocalDate to,
                                       int page, int size) {
        Specification<ExchangeRate> spec = (root, query, cb) -> {
            Predicate p = cb.conjunction();
            if (StringUtils.hasText(currencyCode)) {
                p = cb.and(p, cb.equal(root.get("currencyCode"), currencyCode.trim().toUpperCase(Locale.ROOT)));
            }
            if (source != null) {
                p = cb.and(p, cb.equal(root.get("source"), source));
            }
            if (from != null) {
                p = cb.and(p, cb.greaterThanOrEqualTo(root.get("rateDate"), from));
            }
            if (to != null) {
                p = cb.and(p, cb.lessThanOrEqualTo(root.get("rateDate"), to));
            }
            return p;
        };
        Sort sort = Sort.by(Sort.Direction.DESC, "rateDate").and(Sort.by("currencyCode", "source"));
        return repo.findAll(spec, PageRequest.of(page, size, sort));
    }

    public ExchangeRate findById(UUID id) {
        return repo.findById(id).orElseThrow(() -> new NotFoundException("ExchangeRate", id));
    }

    /** Latest default-source rate of every active foreign currency, for the cards on the rates tab. */
    public List<LatestRate> latestRates() {
        RateSource source = defaultSource();
        LocalDate today = today();
        int maxAge = maxAgeDays();
        List<LatestRate> result = new ArrayList<>();
        for (Currency currency : currencyRepo.findByEnabledTrueAndBaseCurrencyFalseOrderByCodeAsc()) {
            Optional<ExchangeRate> latest = repo.findFirstByCurrencyCodeAndSourceAndRateDateLessThanEqualOrderByRateDateDesc(
                    currency.getCode(), source, today);
            if (latest.isEmpty()) {
                result.add(new LatestRate(currency, null, null, true, null));
                continue;
            }
            ExchangeRate rate = latest.get();
            long age = ChronoUnit.DAYS.between(rate.getRateDate(), today);
            BigDecimal change = repo.findFirstByCurrencyCodeAndSourceAndRateDateLessThanOrderByRateDateDesc(
                            currency.getCode(), source, rate.getRateDate())
                    .map(previous -> CurrencyMath.changePercent(previous.getRate(), rate.getRate()))
                    .orElse(null);
            result.add(new LatestRate(currency, rate, age, age > maxAge, change));
        }
        return result;
    }

    // ---------------------------------------------------------------- recording rates

    @Transactional
    public ExchangeRate create(ExchangeRateDto dto) {
        Currency currency = activeForeign(dto.getCurrencyCode())
                .orElseThrow(() -> BusinessException.onField("currencyCode", "rate.currency.invalid", dto.getCurrencyCode()));
        if (dto.getRateDate().isAfter(today())) {
            throw BusinessException.onField("rateDate", "rate.date.future");
        }
        if (repo.findByCurrencyCodeAndRateDateAndSource(currency.getCode(), dto.getRateDate(), dto.getSource()).isPresent()) {
            throw BusinessException.onField("rateDate", "rate.exists", currency.getCode(), dto.getRateDate(), dto.getSource().name());
        }
        if (!dto.isConfirmed()) {
            checkJump(currency.getCode(), dto.getSource(), dto.getRateDate(), dto.getRate());
        }
        ExchangeRate rate = new ExchangeRate();
        rate.setCurrencyCode(currency.getCode());
        rate.setRateDate(dto.getRateDate());
        rate.setSource(dto.getSource());
        rate.setRate(dto.getRate());
        rate.setNote(StringUtils.hasText(dto.getNote()) ? dto.getNote().trim() : null);
        return repo.save(rate);
    }

    /**
     * Corrects the rate or note of a recorded rate. The caller wraps this in AuditContext.withReason so
     * the change log keeps the reason; it is required here too. Documents that already used the old
     * rate keep it.
     */
    @Transactional
    public Correction correct(UUID id, ExchangeRateDto dto) {
        if (!StringUtils.hasText(dto.getReason())) {
            throw BusinessException.onField("reason", "rate.reason.required");
        }
        ExchangeRate rate = findById(id);
        BigDecimal oldRate = rate.getRate();
        String note = StringUtils.hasText(dto.getNote()) ? dto.getNote().trim() : null;
        boolean rateChanged = oldRate.compareTo(dto.getRate()) != 0;
        if (!rateChanged && Objects.equals(rate.getNote(), note)) {
            return new Correction(rate, oldRate, false);
        }
        if (rateChanged && !dto.isConfirmed()) {
            // Compare with the rate before this one, not with the old value: the old value may be the typo.
            checkJump(rate.getCurrencyCode(), rate.getSource(), rate.getRateDate(), dto.getRate());
        }
        rate.setRate(dto.getRate());
        rate.setNote(note);
        return new Correction(rate, oldRate, true);
    }

    /**
     * Imports a rate file for one source, all or nothing: every line is checked first and nothing is
     * saved if any line has a problem. A line equal to a recorded rate is counted as unchanged; a line
     * that differs from a recorded rate is a problem (corrections need a reason, one by one).
     */
    @Transactional
    public ImportResult importCsv(String content, RateSource source, boolean confirmed, String fileName) {
        RateCsv.Parsed parsed = RateCsv.parse(content, MAX_IMPORT_LINES);
        List<RateCsv.Problem> problems = new ArrayList<>(parsed.problems());
        Map<String, Currency> foreign = currencyRepo.findByEnabledTrueAndBaseCurrencyFalseOrderByCodeAsc().stream()
                .collect(Collectors.toMap(Currency::getCode, Function.identity()));
        LocalDate today = today();
        String note = StringUtils.hasText(fileName) ? truncate("Imported from " + fileName, 255) : "Imported";

        List<RateCsv.Line> lines = parsed.lines().stream()
                .sorted(Comparator.comparing(RateCsv.Line::currencyCode).thenComparing(RateCsv.Line::date))
                .toList();
        Set<String> seen = new HashSet<>();
        Map<String, RateCsv.Line> lastInFile = new HashMap<>();
        List<ExchangeRate> toSave = new ArrayList<>();
        int unchanged = 0;
        for (RateCsv.Line line : lines) {
            if (!foreign.containsKey(line.currencyCode())) {
                problems.add(new RateCsv.Problem(line.number(), "rate.import.notActive", line.currencyCode()));
                continue;
            }
            if (line.date().isAfter(today)) {
                problems.add(new RateCsv.Problem(line.number(), "rate.date.future"));
                continue;
            }
            if (!seen.add(line.currencyCode() + "|" + line.date())) {
                problems.add(new RateCsv.Problem(line.number(), "rate.import.duplicate", line.currencyCode(), line.date()));
                continue;
            }
            Optional<ExchangeRate> existing = repo.findByCurrencyCodeAndRateDateAndSource(line.currencyCode(), line.date(), source);
            if (existing.isPresent()) {
                if (existing.get().getRate().compareTo(line.rate()) == 0) {
                    unchanged++;
                } else {
                    problems.add(new RateCsv.Problem(line.number(), "rate.import.conflict", line.currencyCode(),
                            line.date(), plain(existing.get().getRate())));
                }
                continue;
            }
            if (!confirmed) {
                // Previous rate: the later of the recorded one and the previous line of this file.
                BigDecimal previousRate = null;
                LocalDate previousDate = null;
                Optional<ExchangeRate> recorded = repo.findFirstByCurrencyCodeAndSourceAndRateDateLessThanOrderByRateDateDesc(
                        line.currencyCode(), source, line.date());
                if (recorded.isPresent()) {
                    previousRate = recorded.get().getRate();
                    previousDate = recorded.get().getRateDate();
                }
                RateCsv.Line before = lastInFile.get(line.currencyCode());
                if (before != null && (previousDate == null || before.date().isAfter(previousDate))) {
                    previousRate = before.rate();
                    previousDate = before.date();
                }
                if (previousRate != null && CurrencyMath.isLargeChange(previousRate, line.rate(), LARGE_CHANGE_PERCENT)) {
                    problems.add(new RateCsv.Problem(line.number(), "rate.import.largeChange", line.currencyCode(),
                            CurrencyMath.changePercent(previousRate, line.rate()), plain(previousRate), previousDate));
                }
            }
            lastInFile.put(line.currencyCode(), line);

            ExchangeRate rate = new ExchangeRate();
            rate.setCurrencyCode(line.currencyCode());
            rate.setRateDate(line.date());
            rate.setSource(source);
            rate.setRate(line.rate());
            rate.setNote(note);
            toSave.add(rate);
        }
        if (!problems.isEmpty()) {
            problems.sort(Comparator.comparingInt(RateCsv.Problem::line));
            return new ImportResult(0, unchanged, problems);
        }
        repo.saveAll(toSave);
        return new ImportResult(toSave.size(), unchanged, List.of());
    }

    private Optional<Currency> activeForeign(String code) {
        return currencyRepo.findByCode(code).filter(c -> c.isEnabled() && !c.isBaseCurrency());
    }

    private void checkJump(String code, RateSource source, LocalDate date, BigDecimal rate) {
        repo.findFirstByCurrencyCodeAndSourceAndRateDateLessThanOrderByRateDateDesc(code, source, date)
                .filter(previous -> CurrencyMath.isLargeChange(previous.getRate(), rate, LARGE_CHANGE_PERCENT))
                .ifPresent(previous -> {
                    throw BusinessException.onField("rate", "rate.largeChange",
                            CurrencyMath.changePercent(previous.getRate(), rate), plain(previous.getRate()), previous.getRateDate());
                });
    }

    /** A rate as typed, for messages: MessageFormat would round it to 3 decimals. */
    private static String plain(BigDecimal rate) {
        return rate.stripTrailingZeros().toPlainString();
    }

    private static String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max);
    }
}
