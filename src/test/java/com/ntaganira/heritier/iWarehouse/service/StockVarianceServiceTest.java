package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.config.NumberFormats;
import com.ntaganira.heritier.iWarehouse.entity.StockCount;
import com.ntaganira.heritier.iWarehouse.entity.StockUnit;
import com.ntaganira.heritier.iWarehouse.entity.StockVariance;
import com.ntaganira.heritier.iWarehouse.enums.*;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.repository.StockCountLineRepository;
import com.ntaganira.heritier.iWarehouse.repository.StockUnitRepository;
import com.ntaganira.heritier.iWarehouse.repository.StockVarianceRepository;
import com.ntaganira.heritier.iWarehouse.security.AppUserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : StockVarianceServiceTest.java
 * - Date      : 2026. 10. 16.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : Count variances (IMP 16, VR-010): opened with their value and class when a count closes with differences, the Board told of material ones; resolved with the root cause, the corrective action and the officer responsible; escalated once when not resolved in time.
 * </pre>
 */
class StockVarianceServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-15T08:00:00Z"), ZoneId.of("Africa/Kigali"));

    private final List<StockVariance> saved = new ArrayList<>();
    private StockVarianceRepository repo;
    private Notifier notifier;
    private StockVarianceService service;
    private final StockCount count = new StockCount();

    @BeforeEach
    void setUp() {
        repo = mock(StockVarianceRepository.class);
        when(repo.save(any())).thenAnswer(a -> {
            StockVariance v = a.getArgument(0);
            v.setId(UUID.randomUUID());
            saved.add(v);
            return v;
        });
        when(repo.lockById(any())).thenAnswer(a -> saved.stream().filter(v -> v.getId().equals(a.getArgument(0))).findFirst());
        SettingService settings = mock(SettingService.class);
        when(settings.getDecimal(SettingKey.COUNT_VARIANCE_MODERATE_PERCENT)).thenReturn(new BigDecimal("0.5"));
        when(settings.getDecimal(SettingKey.COUNT_VARIANCE_MATERIAL_PERCENT)).thenReturn(new BigDecimal("2"));
        when(settings.getDecimal(SettingKey.COUNT_VARIANCE_TOLERANCE_PERCENT)).thenReturn(new BigDecimal("0.5"));
        when(settings.getInt(SettingKey.COUNT_VARIANCE_DAYS)).thenReturn(5);
        DocumentNumberService numbers = mock(DocumentNumberService.class);
        when(numbers.next(DocumentType.STOCK_VARIANCE)).thenReturn("VAR-WH-2026-000001", "VAR-WH-2026-000002");
        notifier = mock(Notifier.class);
        service = new StockVarianceService(repo, mock(StockCountLineRepository.class), mock(StockUnitRepository.class), numbers, settings,
                notifier, new NumberFormats(), CLOCK);
        count.setId(UUID.randomUUID());
        count.setNumber("CNT-WH-2026-000009");
        AppUserPrincipal principal = new AppUserPrincipal(7L, "supervisor1", "supervisor1", "x", true, true, List.of());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void aCountWithDifferencesOpensAMaterialVarianceToldToTheBoard() {
        StockUnit a = unit("U-1", "100000.00");
        StockUnit b = unit("U-2", "100000.00");
        StockUnit c = unit("U-3", "50000.00");
        Map<UUID, StockUnit> units = Map.of(a.getId(), a, b.getId(), b, c.getId(), c);
        List<StockCounting.Line> lines = List.of(line(CountOutcome.MATCHED, a), line(CountOutcome.MISSING, b), line(CountOutcome.MISPLACED, c));
        StockVariance v = service.open(count, lines, units, List.of(a, b, c));
        assertThat(v.getNumber()).isEqualTo("VAR-WH-2026-000001");
        assertThat(v.getMissingUnits()).isEqualTo(1);
        assertThat(v.getMisplacedUnits()).isEqualTo(1);
        assertThat(v.getMissingValue()).isEqualByComparingTo("100000");
        assertThat(v.getExpectedValue()).isEqualByComparingTo("250000");
        assertThat(v.getVariancePercent()).isEqualByComparingTo("40.00");
        assertThat(v.getClassification()).isEqualTo(VarianceClass.MATERIAL);
        assertThat(v.isAboveTolerance()).isTrue();
        assertThat(v.getDueOn()).isEqualTo(LocalDate.of(2026, 10, 22));
        assertThat(v.getBoardNotifiedAt()).isNotNull();
        verify(notifier).holders(eq("ALERT_VARIANCE"), eq(7L), eq(NotificationKind.VARIANCE), eq("notify.variance.opened"), any(), any(), any(String[].class));
        verify(notifier).holders(eq("ALERT_MATERIAL_VARIANCE"), eq(7L), eq(NotificationKind.VARIANCE), eq("notify.variance.material"), any(), any(),
                any(String[].class));
        // A count where everything matched opens none
        assertThat(service.open(count, List.of(line(CountOutcome.MATCHED, a)), units, List.of(a))).isNull();
    }

    @Test
    void aVarianceIsResolvedWithItsCauseActionAndOfficerOrEscalatedWhenLate() {
        StockUnit a = unit("U-1", "100000.00");
        StockVariance v = service.open(count, List.of(line(CountOutcome.MISSING, a)), Map.of(a.getId(), a), List.of(a));
        assertThatThrownBy(() -> service.resolve(v.getId(), new StockVarianceService.Form(null, "x", "y", null)))
                .isInstanceOf(BusinessException.class).hasFieldOrPropertyWithValue("messageKey", "variance.rootCause.required");
        assertThatThrownBy(() -> service.resolve(v.getId(), new StockVarianceService.Form(VarianceCause.THEFT, " ", "y", null)))
                .isInstanceOf(BusinessException.class).hasFieldOrPropertyWithValue("messageKey", "variance.action.required");
        assertThatThrownBy(() -> service.resolve(v.getId(), new StockVarianceService.Form(VarianceCause.THEFT, "Gate search", "", null)))
                .isInstanceOf(BusinessException.class).hasFieldOrPropertyWithValue("messageKey", "variance.officer.required");

        // Not resolved by its due day: escalated once
        when(repo.findByStatusAndDueOnBefore(eq(VarianceStatus.OPEN), any())).thenAnswer(x -> saved.stream()
                .filter(s -> s.getStatus() == VarianceStatus.OPEN && s.getDueOn().isBefore(x.getArgument(1))).toList());
        assertThat(service.checkOverdue()).isZero();
        v.setDueOn(LocalDate.of(2026, 10, 14));
        assertThat(service.checkOverdue()).isEqualTo(1);
        assertThat(v.getStatus()).isEqualTo(VarianceStatus.ESCALATED);
        assertThat(service.checkOverdue()).isZero();

        service.resolve(v.getId(), new StockVarianceService.Form(VarianceCause.MISPLACEMENT, " Racks relabelled ", "Warehouse Manager", null));
        assertThat(v.getStatus()).isEqualTo(VarianceStatus.RESOLVED);
        assertThat(v.getCorrectiveAction()).isEqualTo("Racks relabelled");
        assertThat(v.getResolvedBy()).isEqualTo("supervisor1");
        assertThatThrownBy(() -> service.resolve(v.getId(), new StockVarianceService.Form(VarianceCause.THEFT, "x", "y", null)))
                .isInstanceOf(BusinessException.class).hasFieldOrPropertyWithValue("messageKey", "variance.resolved");
    }

    private static StockUnit unit(String code, String cost) {
        StockUnit u = new StockUnit();
        u.setId(UUID.randomUUID());
        u.setCode(code);
        u.setUnitCost(new BigDecimal(cost));
        u.setStatus(StockStatus.AVAILABLE);
        return u;
    }

    private static StockCounting.Line line(CountOutcome outcome, StockUnit u) {
        return new StockCounting.Line(outcome, u.getCode(), new StockCounting.Unit(u.getId(), u.getCode(), u.getStatus(), null), null, null);
    }
}
