package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.config.NumberFormats;
import com.ntaganira.heritier.iWarehouse.dto.DamageReportDto;
import com.ntaganira.heritier.iWarehouse.dto.StockAdjustmentDto;
import com.ntaganira.heritier.iWarehouse.entity.*;
import com.ntaganira.heritier.iWarehouse.enums.*;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.repository.*;
import com.ntaganira.heritier.iWarehouse.security.AppUserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
 * - File      : DamageServiceTest.java
 * - Date      : 2026. 10. 16.
 * - User      : Hntaganira
 * - Desc      : Damage reports (IMP 13, F-07; decision D5): glass on a rack, held by nothing, reported with its cost as the
 *               estimated loss, the Internal Controller told above the tolerance; inspected by another person, approved by a
 *               third within the Delegation of Authority: units written off on one adjustment already approved or put back,
 *               the recovery posted; rejected or cancelled with a reason.
 * </pre>
 */
class DamageServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-16T08:00:00Z"), ZoneId.of("Africa/Kigali"));

    private final Location rack = new Location();
    private final Product clear6 = new Product();
    private final List<StockUnit> units = new ArrayList<>();
    private final Map<UUID, String> holds = new HashMap<>();
    private DamageReport saved;
    private StockAdjustmentService adjustmentService;
    private PostingService postingService;
    private DelegationOfAuthority authority;
    private Notifier notifier;
    private CustomerRepository customerRepo;
    private SettingService settings;
    private DamageService service;

    @BeforeEach
    void setUp() {
        rack.setId(UUID.randomUUID());
        rack.setCode("WH-A-R01");
        rack.setType(LocationType.RACK);
        rack.setEnabled(true);
        clear6.setId(UUID.randomUUID());
        clear6.setCode("CLR-6");
        DamageReportRepository repo = mock(DamageReportRepository.class);
        when(repo.save(any())).thenAnswer(a -> {
            DamageReport d = a.getArgument(0);
            d.setId(UUID.randomUUID());
            d.getLines().forEach(l -> l.setId(UUID.randomUUID()));
            saved = d;
            return d;
        });
        when(repo.lockById(any())).thenAnswer(a -> Optional.ofNullable(saved));
        when(repo.findDetailedById(any())).thenAnswer(a -> Optional.ofNullable(saved));
        StockUnitRepository unitRepo = mock(StockUnitRepository.class);
        when(unitRepo.findByCodeIn(any())).thenAnswer(a -> {
            Collection<String> codes = a.getArgument(0);
            return units.stream().filter(u -> codes.contains(u.getCode())).toList();
        });
        StockService stockService = mock(StockService.class);
        when(stockService.locationsById()).thenReturn(Map.of(rack.getId(), rack));
        when(stockService.holds(any())).thenAnswer(a -> holds);
        adjustmentService = mock(StockAdjustmentService.class);
        when(adjustmentService.createApproved(any(), any(), any(), any())).thenAnswer(a -> {
            StockAdjustment adj = new StockAdjustment();
            adj.setId(UUID.randomUUID());
            adj.setNumber("ADJ-WH-2026-000020");
            return adj;
        });
        postingService = mock(PostingService.class);
        when(postingService.damageRecovery(any())).thenAnswer(a -> {
            JournalEntry e = new JournalEntry();
            e.setNumber("JV-WH-2026-000200");
            return e;
        });
        DocumentNumberService numbers = mock(DocumentNumberService.class);
        when(numbers.next(DocumentType.DAMAGE_REPORT)).thenReturn("DMG-WH-2026-000001");
        settings = mock(SettingService.class);
        when(settings.getBoolean(SettingKey.DAMAGE_INSPECTION)).thenReturn(true);
        when(settings.getDecimal(SettingKey.DAMAGE_ALERT_VALUE)).thenReturn(new BigDecimal("500000"));
        notifier = mock(Notifier.class);
        authority = mock(DelegationOfAuthority.class);
        customerRepo = mock(CustomerRepository.class);
        service = new DamageService(repo, unitRepo, stockService, adjustmentService, postingService, numbers, settings, notifier,
                new NumberFormats(), authority, customerRepo, mock(SupplierRepository.class), mock(DriverRepository.class), CLOCK);
        signIn(5L, "store1");
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void damagedGlassIsReportedWithItsCostAndTheControllerToldAboveTheTolerance() {
        unit("U-WH-000041", StockStatus.AVAILABLE, "252628.86");
        unit("U-WH-000042", StockStatus.RESERVED, "252628.86");
        DamageReport d = service.report(form("u-wh-000041\nU-WH-000042"));
        assertThat(d.getNumber()).isEqualTo("DMG-WH-2026-000001");
        assertThat(d.getStatus()).isEqualTo(DamageStatus.REPORTED);
        assertThat(d.getLines()).extracting(DamageReportLine::getUnitCode).containsExactly("U-WH-000041", "U-WH-000042");
        assertThat(d.getEstimatedLoss()).isEqualByComparingTo("505257.72");
        assertThat(d.getReportedBy()).isEqualTo("store1");
        verify(notifier).holders(eq("INSPECT_DAMAGE"), eq(5L), eq(NotificationKind.DAMAGE), eq("notify.damage.reported"), any(), any(), any(String[].class));
        verify(notifier).holders(eq("ALERT_DAMAGE"), eq(5L), eq(NotificationKind.DAMAGE), eq("notify.damage.alert"), any(), any(), any(String[].class));
    }

    @Test
    void onlyGlassOnARackHeldByNothingIsReported() {
        unit("U-1", StockStatus.SOLD, "100.00");
        unit("U-2", StockStatus.AVAILABLE, "100.00");
        assertThatThrownBy(() -> service.report(form("U-9")))
                .isInstanceOf(BusinessException.class).hasFieldOrPropertyWithValue("messageKey", "damage.unit.unknown");
        assertThatThrownBy(() -> service.report(form("U-1")))
                .isInstanceOf(BusinessException.class).hasFieldOrPropertyWithValue("messageKey", "damage.unit.notInStock");
        holds.put(units.get(1).getId(), "TRP-WH-2026-000013");
        assertThatThrownBy(() -> service.report(form("U-2")))
                .isInstanceOf(BusinessException.class).hasFieldOrPropertyWithValue("messageKey", "damage.unit.held");
        holds.clear();
        DamageReportDto later = form("U-2");
        later.setOccurredOn(LocalDate.of(2026, 10, 17));
        assertThatThrownBy(() -> service.report(later))
                .isInstanceOf(BusinessException.class).hasFieldOrPropertyWithValue("messageKey", "damage.occurredOn.future");
        DamageReportDto tooMuch = form("U-2");
        tooMuch.setRecoverFrom(RecoveryFrom.INSURER);
        tooMuch.setRecoverParty("Radiant Insurance");
        tooMuch.setRecoverAmount(new BigDecimal("101"));
        assertThatThrownBy(() -> service.report(tooMuch))
                .isInstanceOf(BusinessException.class).hasFieldOrPropertyWithValue("messageKey", "damage.recoverAmount.tooMuch");
        verifyNoInteractions(notifier);
    }

    @Test
    void anotherPersonInspectsAndAThirdApprovesUnitByUnitWithTheRecovery() {
        StockUnit a = unit("U-1", StockStatus.AVAILABLE, "60000.00");
        StockUnit b = unit("U-2", StockStatus.AVAILABLE, "40000.00");
        Customer customer = new Customer();
        customer.setId(UUID.randomUUID());
        customer.setName("Umucyo Builders Ltd");
        customer.setEnabled(true);
        when(customerRepo.findById(customer.getId())).thenReturn(Optional.of(customer));
        DamageReportDto dto = form("U-1\nU-2");
        dto.setCause(DamageCause.CUSTOMER_RETURN);
        dto.setRecoverFrom(RecoveryFrom.CUSTOMER);
        dto.setRecoverCustomerId(customer.getId());
        dto.setRecoverAmount(new BigDecimal("30000"));
        DamageReport d = service.report(dto);
        assertThat(d.getRecoverParty()).isEqualTo("Umucyo Builders Ltd");

        // Not decided before the inspection; the reporter neither inspects nor decides
        assertThatThrownBy(() -> service.approve(d.getId(), Map.of(), null))
                .isInstanceOf(BusinessException.class).hasFieldOrPropertyWithValue("messageKey", "damage.notInspected");
        assertThatThrownBy(() -> service.inspect(d.getId(), "Cracked"))
                .isInstanceOf(BusinessException.class).hasFieldOrPropertyWithValue("messageKey", "damage.ownInspection");
        signIn(6L, "dispatch1");
        assertThatThrownBy(() -> service.inspect(d.getId(), " "))
                .isInstanceOf(BusinessException.class).hasFieldOrPropertyWithValue("messageKey", "damage.inspection.note");
        service.inspect(d.getId(), "U-1 cracked across; U-2 only scratched");
        assertThat(d.getStatus()).isEqualTo(DamageStatus.INSPECTED);
        assertThatThrownBy(() -> service.approve(d.getId(), Map.of(), null))
                .isInstanceOf(BusinessException.class).hasFieldOrPropertyWithValue("messageKey", "damage.ownDecision");

        signIn(9L, "supply1");
        UUID lineB = d.getLines().get(1).getId();
        service.approve(d.getId(), Map.of(lineB, DamageOutcome.BACK_TO_STOCK), "Agreed");
        assertThat(d.getStatus()).isEqualTo(DamageStatus.APPROVED);
        assertThat(d.getDecidedBy()).isEqualTo("supply1");
        assertThat(d.getLines()).extracting(DamageReportLine::getOutcome).containsExactly(DamageOutcome.WRITE_OFF, DamageOutcome.BACK_TO_STOCK);
        verify(authority).require(AuthorityDecision.STOCK_ADJUSTMENT, new BigDecimal("100000.00"), "Agreed");
        ArgumentCaptor<StockAdjustmentDto> written = ArgumentCaptor.forClass(StockAdjustmentDto.class);
        verify(adjustmentService).createApproved(written.capture(), eq("store1"), eq(5L), any());
        assertThat(written.getValue().getLines()).extracting(StockAdjustmentDto.Line::getUnitCode).containsExactly("U-1");
        assertThat(written.getValue().getLines().get(0).getCause()).isEqualTo(WriteOffCause.DAMAGED);
        assertThat(d.getAdjustmentNumber()).isEqualTo("ADJ-WH-2026-000020");
        verify(postingService).damageRecovery(d);
        assertThat(d.getRecoveryJournal()).isEqualTo("JV-WH-2026-000200");
        assertThat(a.getStatus()).isEqualTo(StockStatus.AVAILABLE);      // the write-off itself goes through the adjustment
        assertThat(b.getStatus()).isEqualTo(StockStatus.AVAILABLE);
    }

    @Test
    void aReportIsRejectedByAnotherOrCancelledByItsReporterWithAReason() {
        when(settings.getBoolean(SettingKey.DAMAGE_INSPECTION)).thenReturn(false);
        unit("U-1", StockStatus.AVAILABLE, "60000.00");
        DamageReport d = service.report(form("U-1"));
        verify(notifier).holders(eq("APPROVE_DAMAGE"), eq(5L), eq(NotificationKind.DAMAGE), eq("notify.damage.reported"), any(), any(), any(String[].class));
        signIn(9L, "supply1");
        assertThatThrownBy(() -> service.cancel(d.getId(), "Wrong unit"))
                .isInstanceOf(BusinessException.class).hasFieldOrPropertyWithValue("messageKey", "damage.cancel.notReporter");
        assertThatThrownBy(() -> service.reject(d.getId(), ""))
                .isInstanceOf(BusinessException.class).hasFieldOrPropertyWithValue("messageKey", "damage.reason.required");
        service.reject(d.getId(), "Not damaged: the mark wipes off");
        assertThat(d.getStatus()).isEqualTo(DamageStatus.REJECTED);
        assertThat(d.isOpen()).isFalse();
        assertThatThrownBy(() -> service.reject(d.getId(), "again"))
                .isInstanceOf(BusinessException.class).hasFieldOrPropertyWithValue("messageKey", "damage.notOpen");
        verify(adjustmentService, never()).createApproved(any(), any(), any(), any());
    }

    private DamageReportDto form(String codes) {
        DamageReportDto dto = new DamageReportDto();
        dto.setLocationId(rack.getId());
        dto.setOccurredOn(LocalDate.of(2026, 10, 16));
        dto.setCause(DamageCause.HANDLING);
        dto.setDescription("Slipped off the A-frame while unloading");
        dto.setCodes(codes);
        return dto;
    }

    private StockUnit unit(String code, StockStatus status, String cost) {
        StockUnit u = new StockUnit();
        u.setId(UUID.randomUUID());
        u.setCode(code);
        u.setProduct(clear6);
        u.setStatus(status);
        u.setLocation(rack);
        u.setAreaM2(new BigDecimal("7.2225"));
        u.setUnitCost(new BigDecimal(cost));
        units.add(u);
        return u;
    }

    private static void signIn(long id, String username) {
        AppUserPrincipal principal = new AppUserPrincipal(id, username, username, "x", true, true, List.of());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }
}
