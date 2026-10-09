package com.ntaganira.heritier.iWarehouse.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.dto
 * - File      : ManualJournalDto.java
 * - Date      : 2026. 10. 09.
 * - User      : Hntaganira
 * - Desc      : The manual journal form (ACC-05): its date, description and lines (an account, a debit or a
 *               credit in RWF, a memo). Empty rows are dropped before validation; the service checks the
 *               accounts and that the lines balance.
 * </pre>
 */
@Getter
@Setter
@NoArgsConstructor
public class ManualJournalDto {

    @NotNull(message = "{manualJournal.date.required}")
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate entryDate;

    @NotBlank(message = "{manualJournal.description.required}")
    @Size(max = 255, message = "{manualJournal.description.size}")
    private String description;

    @Valid
    private List<Line> lines = new ArrayList<>();

    /** One line: an account and a debit or a credit (RWF), with a memo. */
    @Getter
    @Setter
    @NoArgsConstructor
    public static class Line {

        @NotNull(message = "{manualJournal.line.account.required}")
        private UUID accountId;

        @DecimalMin(value = "0", message = "{manualJournal.line.amount.invalid}")
        @Digits(integer = 16, fraction = 2, message = "{manualJournal.line.amount.invalid}")
        private BigDecimal debit;

        @DecimalMin(value = "0", message = "{manualJournal.line.amount.invalid}")
        @Digits(integer = 16, fraction = 2, message = "{manualJournal.line.amount.invalid}")
        private BigDecimal credit;

        @Size(max = 255, message = "{manualJournal.line.memo.size}")
        private String memo;

        public Line(UUID accountId, BigDecimal debit, BigDecimal credit, String memo) {
            this.accountId = accountId;
            this.debit = debit;
            this.credit = credit;
            this.memo = memo;
        }

        /** A row left empty on the form: dropped before validation. */
        public boolean isBlank() {
            return accountId == null && debit == null && credit == null && !StringUtils.hasText(memo);
        }
    }
}
