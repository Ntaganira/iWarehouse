package com.ntaganira.heritier.iWarehouse.dto;

import com.ntaganira.heritier.iWarehouse.entity.PayRunLine;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.dto
 * - File      : PayRunLinesDto.java
 * - Date      : 2026. 10. 11.
 * - User      : Hntaganira
 * - Desc      : A draft run's lines as the month changes them: earnings and deductions per employee, whole RWF. PAYE, RSSB,
 *               CBHI and the net pay are worked out by the server, never typed.
 * </pre>
 */
@Getter
@Setter
@NoArgsConstructor
public class PayRunLinesDto {

    @Valid
    private List<Line> lines = new ArrayList<>();

    public static PayRunLinesDto of(List<PayRunLine> lines) {
        PayRunLinesDto dto = new PayRunLinesDto();
        for (PayRunLine l : lines) {
            Line row = new Line();
            row.setId(l.getId());
            row.setBasePay(plain(l.getBasePay()));
            row.setTransportAllowance(plain(l.getTransportAllowance()));
            row.setHousingAllowance(plain(l.getHousingAllowance()));
            row.setOtherAllowance(plain(l.getOtherAllowance()));
            row.setOvertime(plain(l.getOvertime()));
            row.setBonus(plain(l.getBonus()));
            row.setAdvanceDeduction(plain(l.getAdvanceDeduction()));
            row.setShortageDeduction(plain(l.getShortageDeduction()));
            row.setOtherDeduction(plain(l.getOtherDeduction()));
            row.setDeductionNote(l.getDeductionNote());
            dto.getLines().add(row);
        }
        return dto;
    }

    /** 300000.00 as 300000, 0.30 as 0.3: what a number field shows (never 3E+5). */
    static BigDecimal plain(BigDecimal value) {
        return value == null ? null : new BigDecimal(value.stripTrailingZeros().toPlainString());
    }

    @Getter
    @Setter
    @NoArgsConstructor
    public static class Line {

        @NotNull
        private UUID id;

        @NotNull(message = "{payroll.amount.range}")
        @DecimalMin(value = "0", message = "{payroll.amount.range}")
        @Digits(integer = 12, fraction = 0, message = "{payroll.amount.range}")
        private BigDecimal basePay;

        @DecimalMin(value = "0", message = "{payroll.amount.range}")
        @Digits(integer = 12, fraction = 0, message = "{payroll.amount.range}")
        private BigDecimal transportAllowance;

        @DecimalMin(value = "0", message = "{payroll.amount.range}")
        @Digits(integer = 12, fraction = 0, message = "{payroll.amount.range}")
        private BigDecimal housingAllowance;

        @DecimalMin(value = "0", message = "{payroll.amount.range}")
        @Digits(integer = 12, fraction = 0, message = "{payroll.amount.range}")
        private BigDecimal otherAllowance;

        @DecimalMin(value = "0", message = "{payroll.amount.range}")
        @Digits(integer = 12, fraction = 0, message = "{payroll.amount.range}")
        private BigDecimal overtime;

        @DecimalMin(value = "0", message = "{payroll.amount.range}")
        @Digits(integer = 12, fraction = 0, message = "{payroll.amount.range}")
        private BigDecimal bonus;

        @DecimalMin(value = "0", message = "{payroll.amount.range}")
        @Digits(integer = 12, fraction = 0, message = "{payroll.amount.range}")
        private BigDecimal advanceDeduction;

        @DecimalMin(value = "0", message = "{payroll.amount.range}")
        @Digits(integer = 12, fraction = 0, message = "{payroll.amount.range}")
        private BigDecimal shortageDeduction;

        @DecimalMin(value = "0", message = "{payroll.amount.range}")
        @Digits(integer = 12, fraction = 0, message = "{payroll.amount.range}")
        private BigDecimal otherDeduction;

        @Size(max = 255, message = "{payRun.note.size}")
        private String deductionNote;
    }
}
