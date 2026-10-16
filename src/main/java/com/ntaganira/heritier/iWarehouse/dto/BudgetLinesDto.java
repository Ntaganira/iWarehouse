package com.ntaganira.heritier.iWarehouse.dto;

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
 * - File      : BudgetLinesDto.java
 * - Date      : 2026. 10. 16.
 * - User      : Hntaganira
 * - Desc      : A department's budget lines (CMP 04): each an account with its annual amount and twelve months (RWF).
 * </pre>
 */
@Getter
@Setter
@NoArgsConstructor
public class BudgetLinesDto {

    private List<Line> lines = new ArrayList<>();

    @Getter
    @Setter
    @NoArgsConstructor
    public static class Line {

        private UUID accountId;

        /** The year: spread over the months by the form when they are not given one by one. */
        private BigDecimal annual;

        private List<BigDecimal> months = new ArrayList<>();

        public boolean isBlank() {
            return accountId == null && (annual == null || annual.signum() == 0) && months.stream().allMatch(m -> m == null || m.signum() == 0);
        }
    }
}
