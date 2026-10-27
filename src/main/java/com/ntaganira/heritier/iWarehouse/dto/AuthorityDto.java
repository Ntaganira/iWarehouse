package com.ntaganira.heritier.iWarehouse.dto;

import com.ntaganira.heritier.iWarehouse.enums.AuthorityDecision;
import com.ntaganira.heritier.iWarehouse.enums.AuthorityLevel;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.dto
 * - File      : AuthorityDto.java
 * - Date      : 2026. 10. 15.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : The Delegation of Authority form: a row per decision, a cell per office that can be changed (Director,
 *               COO, Managing Director): may it approve, and up to what (blank: no limit).
 * </pre>
 */
@Getter
@Setter
public class AuthorityDto {

    private List<Row> rows = new ArrayList<>();

    @Getter
    @Setter
    public static class Row {
        private AuthorityDecision decision;
        private List<Cell> cells = new ArrayList<>();
    }

    @Getter
    @Setter
    public static class Cell {
        private AuthorityLevel level;
        private boolean allowed;
        private BigDecimal max;
    }
}
