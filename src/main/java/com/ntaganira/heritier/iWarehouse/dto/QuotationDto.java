package com.ntaganira.heritier.iWarehouse.dto;

import com.ntaganira.heritier.iWarehouse.enums.QuoteLineKind;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.format.annotation.DateTimeFormat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.dto
 * - File      : QuotationDto.java
 * - Date      : 2026. 10. 09.
 * - User      : Hntaganira
 * - Desc      : Add/edit draft quotation form (POS-03): customer, name and TIN to print, validity date, notes,
 *               and its rows: whole sheets or a size to cut (with processing, holes and the customer's mark), a
 *               quantity and an optional discount. Prices come from the customer's list when it is saved.
 * </pre>
 */
@Getter
@Setter
@NoArgsConstructor
public class QuotationDto {

    private UUID id;

    @NotNull(message = "{quote.customer.required}")
    private UUID customerId;

    @Size(max = 100, message = "{sale.buyerName.size}")
    private String buyerName;

    private String buyerTin;

    @NotNull(message = "{quote.validUntil.required}")
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate validUntil;

    @Size(max = 500, message = "{po.notes.size}")
    private String notes;

    @Valid
    private List<Line> lines = new ArrayList<>();

    /** One row: glass, whole sheets or a size to cut, size, quantity, processing, holes, mark, discount. */
    @Getter
    @Setter
    @NoArgsConstructor
    public static class Line {

        /** The sheet or size line it edits. */
        private UUID id;

        @NotNull(message = "{po.line.product.required}")
        private UUID productId;

        @NotNull(message = "{quote.line.kind.required}")
        private QuoteLineKind kind = QuoteLineKind.CUSTOM_PIECE;

        @NotNull(message = "{po.line.size.required}")
        @Min(value = 1, message = "{po.line.size.range}")
        @Max(value = 10000, message = "{po.line.size.range}")
        private Integer widthMm;

        @NotNull(message = "{po.line.size.required}")
        @Min(value = 1, message = "{po.line.size.range}")
        @Max(value = 10000, message = "{po.line.size.range}")
        private Integer heightMm;

        @NotNull(message = "{po.line.quantity.required}")
        @Min(value = 1, message = "{sale.custom.quantity}")
        @Max(value = 999, message = "{sale.custom.quantity}")
        private Integer quantity;

        private List<UUID> serviceIds = new ArrayList<>();

        @Min(value = 1, message = "{sale.custom.holes}")
        @Max(value = 50, message = "{sale.custom.holes}")
        private Integer holes;

        @Size(max = 60, message = "{sale.custom.markSize}")
        private String mark;

        @DecimalMin(value = "0", message = "{quote.line.discount.range}")
        @DecimalMax(value = "100", message = "{quote.line.discount.range}")
        @Digits(integer = 3, fraction = 2, message = "{quote.line.discount.range}")
        private BigDecimal discountPercent;

        /** A row with nothing typed in it (its quantity and kind start filled) is dropped before validation. */
        public boolean isBlank() {
            return productId == null && widthMm == null && heightMm == null
                    && (serviceIds == null || serviceIds.isEmpty()) && (mark == null || mark.isBlank()) && discountPercent == null;
        }
    }
}
