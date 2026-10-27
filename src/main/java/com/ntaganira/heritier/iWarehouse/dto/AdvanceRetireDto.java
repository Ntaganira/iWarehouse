package com.ntaganira.heritier.iWarehouse.dto;

import com.ntaganira.heritier.iWarehouse.enums.AdvancePayMethod;
import com.ntaganira.heritier.iWarehouse.enums.ExpenseCategory;
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
 * - File      : AdvanceRetireDto.java
 * - Date      : 2026. 10. 16.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : Retiring a staff advance (CMP 08): the day, what it paid for by category and cost centre with each receipt's
 *               number, and how the rest is settled (returned by the employee, or paid back to them).
 * </pre>
 */
@Getter
@Setter
@NoArgsConstructor
public class AdvanceRetireDto {

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate retiredOn;

    @Valid
    private List<Line> lines = new ArrayList<>();

    private AdvancePayMethod settleMethod;

    private UUID settleFundId;

    @Size(max = 60, message = "{pettyCash.reference.size}")
    private String settleReference;

    @Getter
    @Setter
    @NoArgsConstructor
    public static class Line {

        @NotNull(message = "{pettyCash.voucher.category}")
        private ExpenseCategory category;

        @NotBlank(message = "{expense.line.description.required}")
        @Size(max = 255, message = "{expense.line.description.size}")
        private String description;

        @NotNull(message = "{expense.line.costCentre.required}")
        private UUID costCentreId;

        @NotNull(message = "{pettyCash.amount.required}")
        @DecimalMin(value = "0.01", message = "{pettyCash.amount.required}")
        @Digits(integer = 16, fraction = 2, message = "{pettyCash.amount.required}")
        private BigDecimal amount;

        @Size(max = 60, message = "{pettyCash.voucher.receiptRef.size}")
        private String receiptRef;

        public boolean isBlank() {
            return category == null && !StringUtils.hasText(description) && costCentreId == null && amount == null && !StringUtils.hasText(receiptRef);
        }
    }
}
