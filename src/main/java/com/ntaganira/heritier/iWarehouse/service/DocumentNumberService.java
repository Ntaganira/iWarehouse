package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.dto.NumberSequenceDto;
import com.ntaganira.heritier.iWarehouse.entity.NumberSequence;
import com.ntaganira.heritier.iWarehouse.enums.DocumentType;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.exception.NotFoundException;
import com.ntaganira.heritier.iWarehouse.repository.NumberSequenceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : DocumentNumberService.java
 * - Date      : 2026. 10. 07.
 * - User      : Hntaganira
 * - Desc      : Document numbering per type and branch (MD-07), e.g. INV-WH-2026-000123.
 *               next() locks the sequence row and must run inside the transaction that saves the
 *               document: a rollback gives the number back, so numbers have no gaps and never repeat.
 *               The next number can only go up, and the reset policy is fixed once a number is issued.
 * </pre>
 */
@Service
@Transactional(readOnly = true)
public class DocumentNumberService {

    private final NumberSequenceRepository repo;
    private final SettingService settingService;
    private final Clock clock;

    public DocumentNumberService(NumberSequenceRepository repo, SettingService settingService, Clock clock) {
        this.repo = repo;
        this.settingService = settingService;
        this.clock = clock;
    }

    /** An edited sequence and, when the next number was moved by hand, a line for the activity log. */
    public record SequenceUpdate(NumberSequence sequence, String counterChange) {
    }

    /** Next number for a document of the default branch (Settings, company.branch-code). */
    @Transactional(propagation = Propagation.MANDATORY)
    public String next(DocumentType type) {
        return next(type, settingService.branchCode());
    }

    /**
     * Issues the next number. Call it from the service method that saves the document, never on its
     * own: MANDATORY makes Spring refuse a call outside a transaction.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public String next(DocumentType type, String branchCode) {
        NumberSequence sequence = repo.findForUpdate(type, branchCode)
                .orElseThrow(() -> BusinessException.of("numbering.missing", type.name(), branchCode));
        LocalDate today = LocalDate.now(clock);
        if (DocumentNumbers.isBehind(sequence.getResetPolicy(), sequence.getPeriodKey(), today)) {
            throw BusinessException.of("numbering.periodBehind", sequence.getPrefix(), sequence.getPeriodKey());
        }
        long value = DocumentNumbers.effectiveNext(sequence.getResetPolicy(), sequence.getPeriodKey(),
                sequence.getNextValue(), today);
        sequence.setPeriodKey(DocumentNumbers.periodKey(sequence.getResetPolicy(), today));
        sequence.setNextValue(value + 1);
        sequence.setLastIssuedAt(LocalDateTime.now(clock));
        return DocumentNumbers.format(sequence.getPrefix(), sequence.getBranchCode(), sequence.getResetPolicy(),
                today, value, sequence.getPadding());
    }

    /**
     * Issues the next plain number of a sequence, for the default branch: the EBM invoice number (invcNo, TAX-02) is an
     * integer, not a formatted document number. Same lock and rules as next().
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public long nextSerial(DocumentType type) {
        String branchCode = settingService.branchCode();
        NumberSequence sequence = repo.findForUpdate(type, branchCode)
                .orElseThrow(() -> BusinessException.of("numbering.missing", type.name(), branchCode));
        LocalDate today = LocalDate.now(clock);
        long value = DocumentNumbers.effectiveNext(sequence.getResetPolicy(), sequence.getPeriodKey(), sequence.getNextValue(), today);
        sequence.setPeriodKey(DocumentNumbers.periodKey(sequence.getResetPolicy(), today));
        sequence.setNextValue(value + 1);
        sequence.setLastIssuedAt(LocalDateTime.now(clock));
        return value;
    }

    /**
     * Moves the default branch's sequence up so its next number is at least {@code next}, when numbers were issued
     * elsewhere first (the EBM device's last invoice number). Never moves it down. True when it moved.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean raiseTo(DocumentType type, long next) {
        String branchCode = settingService.branchCode();
        NumberSequence sequence = repo.findForUpdate(type, branchCode)
                .orElseThrow(() -> BusinessException.of("numbering.missing", type.name(), branchCode));
        if (sequence.getNextValue() >= next) {
            return false;
        }
        sequence.setNextValue(next);
        return true;
    }

    /** Every sequence, by branch and then in DocumentType order. */
    public List<NumberSequence> findAll() {
        return repo.findAll().stream()
                .sorted(Comparator.comparing(NumberSequence::getBranchCode)
                        .thenComparing(NumberSequence::getDocType))
                .toList();
    }

    public NumberSequence findById(UUID id) {
        return repo.findById(id).orElseThrow(() -> new NotFoundException("NumberSequence", id));
    }

    /** Value the next document would get today (after a pending year or month rollover). */
    public long nextValue(NumberSequence sequence) {
        return DocumentNumbers.effectiveNext(sequence.getResetPolicy(), sequence.getPeriodKey(),
                sequence.getNextValue(), LocalDate.now(clock));
    }

    /** The number the next document would get, without issuing it. */
    public String preview(NumberSequence sequence) {
        return DocumentNumbers.format(sequence.getPrefix(), sequence.getBranchCode(), sequence.getResetPolicy(),
                LocalDate.now(clock), nextValue(sequence), sequence.getPadding());
    }

    @Transactional
    public NumberSequence create(NumberSequenceDto dto) {
        if (repo.existsByDocTypeAndBranchCode(dto.getDocType(), dto.getBranchCode())) {
            throw BusinessException.onField("docType", "numbering.exists", dto.getDocType().name(), dto.getBranchCode());
        }
        if (repo.existsByPrefixAndBranchCode(dto.getPrefix(), dto.getBranchCode())) {
            throw BusinessException.onField("prefix", "numbering.prefix.taken", dto.getPrefix(), dto.getBranchCode());
        }
        NumberSequence sequence = new NumberSequence();
        sequence.setDocType(dto.getDocType());
        sequence.setBranchCode(dto.getBranchCode());
        sequence.setPrefix(dto.getPrefix());
        sequence.setResetPolicy(dto.getResetPolicy());
        sequence.setPadding(dto.getPadding());
        sequence.setNextValue(dto.getNextValue());
        return repo.save(sequence);
    }

    @Transactional
    public SequenceUpdate update(UUID id, NumberSequenceDto dto) {
        // Lock: a document numbered while this form was open must not be issued again.
        NumberSequence sequence = repo.lockById(id).orElseThrow(() -> new NotFoundException("NumberSequence", id));
        if (repo.existsByPrefixAndBranchCodeAndIdNot(dto.getPrefix(), sequence.getBranchCode(), id)) {
            throw BusinessException.onField("prefix", "numbering.prefix.taken", dto.getPrefix(), sequence.getBranchCode());
        }
        if (sequence.isUsed() && dto.getResetPolicy() != sequence.getResetPolicy()) {
            throw BusinessException.onField("resetPolicy", "numbering.reset.locked");
        }
        LocalDate today = LocalDate.now(clock);
        long floor = DocumentNumbers.effectiveNext(sequence.getResetPolicy(), sequence.getPeriodKey(),
                sequence.getNextValue(), today);
        if (dto.getNextValue() < floor) {
            throw BusinessException.onField("nextValue", "numbering.next.lower", floor);
        }
        String counterChange = dto.getNextValue() != floor
                ? "next number " + floor + " -> " + dto.getNextValue() : null;

        sequence.setPrefix(dto.getPrefix());
        sequence.setResetPolicy(dto.getResetPolicy());
        sequence.setPadding(dto.getPadding());
        if (!sequence.getPeriodKey().isEmpty()) {
            // Already used: the counter now belongs to the current period (applies a pending rollover).
            sequence.setPeriodKey(DocumentNumbers.periodKey(sequence.getResetPolicy(), today));
        }
        sequence.setNextValue(dto.getNextValue());
        return new SequenceUpdate(sequence, counterChange);
    }
}
