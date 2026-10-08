package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.dto.NumberSequenceDto;
import com.ntaganira.heritier.iWarehouse.entity.NumberSequence;
import com.ntaganira.heritier.iWarehouse.enums.DocumentType;
import com.ntaganira.heritier.iWarehouse.enums.ResetPolicy;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.repository.NumberSequenceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Issuing and editing numbering sequences (MD-07) on a fixed date: 7 October 2026 in Kigali. */
class DocumentNumberServiceTest {

    private static final ZoneId KIGALI = ZoneId.of("Africa/Kigali");

    private NumberSequenceRepository repo;
    private DocumentNumberService service;

    @BeforeEach
    void setUp() {
        repo = mock(NumberSequenceRepository.class);
        SettingService settings = mock(SettingService.class);
        when(settings.branchCode()).thenReturn("WH");
        Clock clock = Clock.fixed(LocalDateTime.of(2026, 10, 7, 9, 30).atZone(KIGALI).toInstant(), KIGALI);
        service = new DocumentNumberService(repo, settings, clock);
    }

    @Test
    void issuesTheNextNumberAndMovesTheCounter() {
        NumberSequence invoices = sequence("INV", ResetPolicy.YEARLY, 123, "2026");
        when(repo.findForUpdate(DocumentType.INVOICE, "WH")).thenReturn(Optional.of(invoices));

        assertThat(service.next(DocumentType.INVOICE)).isEqualTo("INV-WH-2026-000123");
        assertThat(service.next(DocumentType.INVOICE)).isEqualTo("INV-WH-2026-000124");
        assertThat(invoices.getNextValue()).isEqualTo(125);
        assertThat(invoices.getLastIssuedAt()).isEqualTo(LocalDateTime.of(2026, 10, 7, 9, 30));
    }

    @Test
    void newYearStartsAgainAtOne() {
        NumberSequence invoices = sequence("INV", ResetPolicy.YEARLY, 900, "2025");
        when(repo.findForUpdate(DocumentType.INVOICE, "WH")).thenReturn(Optional.of(invoices));

        assertThat(service.next(DocumentType.INVOICE)).isEqualTo("INV-WH-2026-000001");
        assertThat(invoices.getPeriodKey()).isEqualTo("2026");
        assertThat(invoices.getNextValue()).isEqualTo(2);
    }

    @Test
    void firstNumberHonoursAStartingValue() {
        NumberSequence invoices = sequence("INV", ResetPolicy.YEARLY, 1250, "");
        when(repo.findForUpdate(DocumentType.INVOICE, "WH")).thenReturn(Optional.of(invoices));

        assertThat(service.next(DocumentType.INVOICE)).isEqualTo("INV-WH-2026-001250");
    }

    @Test
    void missingSequenceIsRefused() {
        when(repo.findForUpdate(DocumentType.TRIP, "V01")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.next(DocumentType.TRIP, "V01"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getMessageKey()).isEqualTo("numbering.missing"));
    }

    @Test
    void clockBehindTheLastNumberIsRefused() {
        NumberSequence invoices = sequence("INV", ResetPolicy.YEARLY, 5, "2027");
        when(repo.findForUpdate(DocumentType.INVOICE, "WH")).thenReturn(Optional.of(invoices));

        assertThatThrownBy(() -> service.next(DocumentType.INVOICE))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getMessageKey()).isEqualTo("numbering.periodBehind"));
        assertThat(invoices.getNextValue()).isEqualTo(5);
    }

    @Test
    void nextNumberCannotGoDown() {
        NumberSequence invoices = used(sequence("INV", ResetPolicy.YEARLY, 10, "2026"));
        when(repo.lockById(invoices.getId())).thenReturn(Optional.of(invoices));

        assertThatThrownBy(() -> service.update(invoices.getId(), dto("INV", ResetPolicy.YEARLY, 9L)))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getField()).isEqualTo("nextValue");
                    assertThat(e.getArgs()).containsExactly(10L);
                });
    }

    @Test
    void raisingTheNextNumberIsReported() {
        NumberSequence invoices = used(sequence("INV", ResetPolicy.YEARLY, 10, "2026"));
        when(repo.lockById(invoices.getId())).thenReturn(Optional.of(invoices));

        DocumentNumberService.SequenceUpdate update = service.update(invoices.getId(), dto("FAC", ResetPolicy.YEARLY, 1250L));

        assertThat(update.counterChange()).isEqualTo("next number 10 -> 1250");
        assertThat(invoices.getPrefix()).isEqualTo("FAC");
        assertThat(service.preview(invoices)).isEqualTo("FAC-WH-2026-001250");
    }

    @Test
    void resetPolicyIsFixedOnceNumbersWereIssued() {
        NumberSequence invoices = used(sequence("INV", ResetPolicy.YEARLY, 10, "2026"));
        when(repo.lockById(invoices.getId())).thenReturn(Optional.of(invoices));

        assertThatThrownBy(() -> service.update(invoices.getId(), dto("INV", ResetPolicy.MONTHLY, 10L)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getField()).isEqualTo("resetPolicy"));
    }

    @Test
    void unusedSequenceMayChangeItsResetPolicy() {
        NumberSequence quotes = sequence("QUO", ResetPolicy.YEARLY, 1, "");
        when(repo.lockById(quotes.getId())).thenReturn(Optional.of(quotes));

        service.update(quotes.getId(), dto("QUO", ResetPolicy.MONTHLY, 1L));

        assertThat(quotes.getResetPolicy()).isEqualTo(ResetPolicy.MONTHLY);
        assertThat(quotes.getPeriodKey()).isEmpty();
    }

    @Test
    void editAfterNewYearAppliesThePendingRollover() {
        NumberSequence invoices = used(sequence("INV", ResetPolicy.YEARLY, 900, "2025"));
        when(repo.lockById(invoices.getId())).thenReturn(Optional.of(invoices));

        DocumentNumberService.SequenceUpdate update = service.update(invoices.getId(), dto("INV", ResetPolicy.YEARLY, 1L));

        assertThat(update.counterChange()).isNull();
        assertThat(invoices.getPeriodKey()).isEqualTo("2026");
        assertThat(invoices.getNextValue()).isEqualTo(1);
    }

    @Test
    void prefixUsedByAnotherSequenceIsRefused() {
        NumberSequence invoices = sequence("INV", ResetPolicy.YEARLY, 1, "");
        when(repo.lockById(invoices.getId())).thenReturn(Optional.of(invoices));
        when(repo.existsByPrefixAndBranchCodeAndIdNot("PO", "WH", invoices.getId())).thenReturn(true);

        assertThatThrownBy(() -> service.update(invoices.getId(), dto("PO", ResetPolicy.YEARLY, 1L)))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getField()).isEqualTo("prefix"));
        verify(repo, never()).save(any());
    }

    private static NumberSequence sequence(String prefix, ResetPolicy policy, long next, String period) {
        NumberSequence s = new NumberSequence();
        s.setId(UUID.randomUUID());
        s.setDocType(DocumentType.INVOICE);
        s.setBranchCode("WH");
        s.setPrefix(prefix);
        s.setResetPolicy(policy);
        s.setPadding(6);
        s.setNextValue(next);
        s.setPeriodKey(period);
        return s;
    }

    private static NumberSequence used(NumberSequence s) {
        s.setLastIssuedAt(LocalDateTime.of(2026, 1, 15, 10, 0));
        return s;
    }

    private static NumberSequenceDto dto(String prefix, ResetPolicy policy, Long next) {
        NumberSequenceDto dto = new NumberSequenceDto();
        dto.setPrefix(prefix);
        dto.setResetPolicy(policy);
        dto.setPadding(6);
        dto.setNextValue(next);
        return dto;
    }
}
