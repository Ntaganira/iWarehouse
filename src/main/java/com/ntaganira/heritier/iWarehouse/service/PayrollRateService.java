package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.dto.PayrollRateDto;
import com.ntaganira.heritier.iWarehouse.entity.PayrollRateSet;
import com.ntaganira.heritier.iWarehouse.enums.PayRunStatus;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.exception.NotFoundException;
import com.ntaganira.heritier.iWarehouse.repository.PayRunRepository;
import com.ntaganira.heritier.iWarehouse.repository.PayrollRateSetRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : PayrollRateService.java
 * - Date      : 2026. 10. 11.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : The payroll rates in force from each date (PAYE bands and rates, RSSB, CBHI). A run takes the set in force on
 *               its month's last day; a set a submitted, posted or paid run uses is never changed: a new set from a later
 *               date takes over instead.
 * </pre>
 */
@Service
@Transactional(readOnly = true)
public class PayrollRateService {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final EnumSet<PayRunStatus> FIXING = EnumSet.of(PayRunStatus.SUBMITTED, PayRunStatus.POSTED, PayRunStatus.PAID);

    private final PayrollRateSetRepository repo;
    private final PayRunRepository runRepo;
    private final Clock clock;

    public PayrollRateService(PayrollRateSetRepository repo, PayRunRepository runRepo, Clock clock) {
        this.repo = repo;
        this.runRepo = runRepo;
        this.clock = clock;
    }

    public List<PayrollRateSet> all() {
        return repo.findAllByOrderByEffectiveFromDesc();
    }

    public PayrollRateSet findById(UUID id) {
        return repo.findById(id).orElseThrow(() -> new NotFoundException("PayrollRateSet", id));
    }

    /** The set in force on a day. */
    public Optional<PayrollRateSet> inForce(LocalDate day) {
        return repo.findFirstByEffectiveFromLessThanEqualOrderByEffectiveFromDesc(day);
    }

    public LocalDate today() {
        return LocalDate.now(clock);
    }

    /** The set in force today. */
    public Optional<PayrollRateSet> inForceToday() {
        return inForce(LocalDate.now(clock));
    }

    /** A run waiting for approval, posted or paid uses it: it never changes. */
    public boolean isUsed(UUID id) {
        return runRepo.existsByRateSet_IdAndStatusIn(id, FIXING);
    }

    /** A new set starts as a copy of the one in force, from next month's first day. */
    public PayrollRateDto newForm() {
        LocalDate next = LocalDate.now(clock).withDayOfMonth(1).plusMonths(1);
        PayrollRateDto dto = inForce(next).map(PayrollRateDto::of).orElseGet(PayrollRateDto::new);
        dto.setId(null);
        dto.setEffectiveFrom(next);
        dto.setNote(null);
        return dto;
    }

    @Transactional
    public PayrollRateSet create(PayrollRateDto dto) {
        if (repo.existsByEffectiveFrom(dto.getEffectiveFrom())) {
            throw BusinessException.onField("effectiveFrom", "payrollRate.from.taken", dto.getEffectiveFrom().format(DAY));
        }
        PayrollRateSet set = new PayrollRateSet();
        apply(set, dto);
        return repo.save(set);
    }

    @Transactional
    public PayrollRateSet update(UUID id, PayrollRateDto dto) {
        PayrollRateSet set = findById(id);
        if (isUsed(id)) {
            throw BusinessException.of("payrollRate.used", set.getEffectiveFrom().format(DAY));
        }
        if (repo.existsByEffectiveFromAndIdNot(dto.getEffectiveFrom(), id)) {
            throw BusinessException.onField("effectiveFrom", "payrollRate.from.taken", dto.getEffectiveFrom().format(DAY));
        }
        apply(set, dto);
        return set;
    }

    private static void apply(PayrollRateSet set, PayrollRateDto dto) {
        if (dto.getPayeBand2().compareTo(dto.getPayeBand1()) <= 0) {
            throw BusinessException.onField("payeBand2", "payrollRate.bands.order");
        }
        if (dto.getPayeBand3().compareTo(dto.getPayeBand2()) <= 0) {
            throw BusinessException.onField("payeBand3", "payrollRate.bands.order");
        }
        set.setEffectiveFrom(dto.getEffectiveFrom());
        set.setPayeBand1(dto.getPayeBand1());
        set.setPayeBand2(dto.getPayeBand2());
        set.setPayeBand3(dto.getPayeBand3());
        set.setPayeRate1(dto.getPayeRate1());
        set.setPayeRate2(dto.getPayeRate2());
        set.setPayeRate3(dto.getPayeRate3());
        set.setPayeRate4(dto.getPayeRate4());
        set.setPayeSecondaryRate(dto.getPayeSecondaryRate());
        set.setPensionEmployeeRate(dto.getPensionEmployeeRate());
        set.setPensionEmployerRate(dto.getPensionEmployerRate());
        set.setOccupationalHazardsRate(dto.getOccupationalHazardsRate());
        set.setMaternityEmployeeRate(dto.getMaternityEmployeeRate());
        set.setMaternityEmployerRate(dto.getMaternityEmployerRate());
        set.setCbhiRate(dto.getCbhiRate());
        set.setTransportInRssbBase(dto.isTransportInRssbBase());
        set.setNote(StringUtils.hasText(dto.getNote()) ? dto.getNote().trim() : null);
    }
}
