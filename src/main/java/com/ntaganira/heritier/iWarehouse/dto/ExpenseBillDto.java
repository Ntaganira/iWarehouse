package com.ntaganira.heritier.iWarehouse.dto;

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
 * - File      : ExpenseBillDto.java
 * - Date      : 2026. 10. 16.
 * - User      : Hntaganira
 * - Desc      : The expense bill form (CMP 01, 05): the supplier and their invoice, its day and due day, whether it is budgeted
 *               or an emergency, the requisition it uses, its lines by category, cost centre and location.
 * </pre>
 */
@Getter
@Setter
@NoArgsConstructor
public class ExpenseBillDto {

    @NotNull(message = "{expense.supplier.required}")
    private UUID supplierId;

    @NotBlank(message = "{expense.supplierRef.required}")
    @Size(max = 60, message = "{expense.supplierRef.size}")
    private String supplierRef;

    @NotNull(message = "{expense.billDate.required}")
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate billDate;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate dueDate;

    @NotBlank(message = "{expense.description.required}")
    @Size(max = 255, message = "{expense.description.size}")
    private String description;

    private boolean budgeted = true;

    private boolean emergency;

    private UUID requisitionId;

    @Valid
    private List<Line> lines = new ArrayList<>();

    @Getter
    @Setter
    @NoArgsConstructor
    public static class Line {

        @NotNull(message = "{expense.line.category.required}")
        private ExpenseCategory category;

        @NotBlank(message = "{expense.line.description.required}")
        @Size(max = 255, message = "{expense.line.description.size}")
        private String description;

        @NotNull(message = "{expense.line.costCentre.required}")
        private UUID costCentreId;

        private UUID locationId;

        @NotNull(message = "{expense.line.net.required}")
        @DecimalMin(value = "0.01", message = "{expense.line.net.range}")
        @Digits(integer = 16, fraction = 2, message = "{expense.line.net.range}")
        private BigDecimal netAmount;

        @DecimalMin(value = "0", message = "{expense.line.vat.range}")
        @Digits(integer = 16, fraction = 2, message = "{expense.line.vat.range}")
        private BigDecimal vatAmount;

        public boolean isBlank() {
            return category == null && !StringUtils.hasText(description) && costCentreId == null && netAmount == null && vatAmount == null;
        }
    }
}
