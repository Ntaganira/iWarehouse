package com.ntaganira.heritier.iWarehouse.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.dto
 * - File      : BudgetRevisionDto.java
 * - Date      : 2026. 10. 16.
 * - User      : Hntaganira
 * - Desc      : A budget revision asked (CMP 04, T.02): the line's new twelve months and the reason.
 * </pre>
 */
@Getter
@Setter
@NoArgsConstructor
public class BudgetRevisionDto {

    private List<BigDecimal> months = new ArrayList<>();

    @NotBlank(message = "{budget.revision.reason}")
    @Size(max = 500, message = "{budget.revision.reason}")
    private String reason;
}
