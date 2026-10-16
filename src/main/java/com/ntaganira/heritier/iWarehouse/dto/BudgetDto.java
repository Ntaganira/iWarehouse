package com.ntaganira.heritier.iWarehouse.dto;

import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.dto
 * - File      : BudgetDto.java
 * - Date      : 2026. 10. 16.
 * - User      : Hntaganira
 * - Desc      : A budget's header (CMP 04): its year (fixed once started), title, the year's landed cost and the demurrage
 *               ceiling per consignment.
 * </pre>
 */
@Getter
@Setter
@NoArgsConstructor
public class BudgetDto {

    @NotNull(message = "{budget.year.required}")
    @Min(value = 2020, message = "{budget.year.range}")
    @Max(value = 2100, message = "{budget.year.range}")
    private Integer budgetYear;

    @Size(max = 150, message = "{budget.title.size}")
    private String title;

    @DecimalMin(value = "0", message = "{budget.amount.range}")
    @Digits(integer = 16, fraction = 2, message = "{budget.amount.range}")
    private BigDecimal landedCostBudget;

    @DecimalMin(value = "0", message = "{budget.amount.range}")
    @Digits(integer = 16, fraction = 2, message = "{budget.amount.range}")
    private BigDecimal demurrageCeiling;
}
