package com.ntaganira.heritier.iWarehouse.dto;

import com.ntaganira.heritier.iWarehouse.enums.ExpenseNature;
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
 * - File      : RequisitionDto.java
 * - Date      : 2026. 10. 16.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : The purchase requisition form (CMP 06): the department, what is needed and why, the budget code, the items
 *               and their estimated cost, the quotes received and the one chosen.
 * </pre>
 */
@Getter
@Setter
@NoArgsConstructor
public class RequisitionDto {

    @NotNull(message = "{requisition.costCentre.required}")
    private UUID costCentreId;

    @NotBlank(message = "{requisition.title.required}")
    @Size(max = 150, message = "{requisition.title.size}")
    private String title;

    @Size(max = 1000, message = "{requisition.justification.size}")
    private String justification;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate neededBy;

    @Size(max = 30, message = "{requisition.budgetCode.size}")
    private String budgetCode;

    private boolean budgeted = true;

    @NotNull(message = "{requisition.nature.required}")
    private ExpenseNature nature = ExpenseNature.OPEX;

    @Size(max = 100, message = "{requisition.sourcingRef.size}")
    private String sourcingRef;

    /** The chosen quote's row on the form (0 = the first). */
    private Integer chosenQuote;

    @Size(max = 500, message = "{requisition.chosenReason.size}")
    private String chosenReason;

    @Valid
    private List<Line> lines = new ArrayList<>();

    @Valid
    private List<Quote> quotes = new ArrayList<>();

    @Getter
    @Setter
    @NoArgsConstructor
    public static class Line {

        @NotBlank(message = "{requisition.line.item.required}")
        @Size(max = 200, message = "{requisition.line.item.size}")
        private String item;

        @NotNull(message = "{requisition.line.quantity.required}")
        @DecimalMin(value = "0.01", message = "{requisition.line.quantity.range}")
        @Digits(integer = 10, fraction = 2, message = "{requisition.line.quantity.range}")
        private BigDecimal quantity;

        @Size(max = 20, message = "{requisition.line.unit.size}")
        private String unit;

        @NotNull(message = "{requisition.line.unitCost.required}")
        @DecimalMin(value = "0", message = "{requisition.line.unitCost.range}")
        @Digits(integer = 16, fraction = 2, message = "{requisition.line.unitCost.range}")
        private BigDecimal unitCost;

        /** A row left empty on the form: dropped before validation. */
        public boolean isBlank() {
            return !StringUtils.hasText(item) && quantity == null && unitCost == null && !StringUtils.hasText(unit);
        }
    }

    @Getter
    @Setter
    @NoArgsConstructor
    public static class Quote {

        private UUID supplierId;

        @Size(max = 150, message = "{requisition.quote.supplier.size}")
        private String supplierName;

        @Size(max = 60, message = "{requisition.quote.ref.size}")
        private String quoteRef;

        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        private LocalDate quoteDate;

        @NotNull(message = "{requisition.quote.amount.required}")
        @DecimalMin(value = "0.01", message = "{requisition.quote.amount.range}")
        @Digits(integer = 16, fraction = 2, message = "{requisition.quote.amount.range}")
        private BigDecimal amount;

        @Size(max = 255, message = "{requisition.quote.note.size}")
        private String note;

        public boolean isBlank() {
            return supplierId == null && !StringUtils.hasText(supplierName) && amount == null && !StringUtils.hasText(quoteRef);
        }
    }
}
