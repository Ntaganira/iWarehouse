package com.ntaganira.heritier.iWarehouse.dto;

import com.ntaganira.heritier.iWarehouse.enums.Incoterm;
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
 * - File      : PurchaseOrderDto.java
 * - Date      : 2026. 10. 08.
 * - User      : Hntaganira
 * - Desc      : Add/edit draft purchase order form (PRC-01): supplier, dates, incoterm and the sheet
 *               lines. The number is given on saving; the currency is the supplier's.
 * </pre>
 */
@Getter
@Setter
@NoArgsConstructor
public class PurchaseOrderDto {

    private UUID id;

    @NotNull(message = "{po.supplier.required}")
    private UUID supplierId;

    @NotNull(message = "{po.orderDate.required}")
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate orderDate;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate expectedDate;

    private Incoterm incoterm;

    @Size(max = 60, message = "{po.supplierRef.size}")
    private String supplierRef;

    @Size(max = 500, message = "{po.notes.size}")
    private String notes;

    @Valid
    private List<Line> lines = new ArrayList<>();

    /** One line of sheets: product, size, quantity and price per m² in the order's currency. */
    @Getter
    @Setter
    @NoArgsConstructor
    public static class Line {

        private UUID id;

        @NotNull(message = "{po.line.product.required}")
        private UUID productId;

        @NotNull(message = "{po.line.size.required}")
        @Min(value = 1, message = "{po.line.size.range}")
        @Max(value = 10000, message = "{po.line.size.range}")
        private Integer widthMm;

        @NotNull(message = "{po.line.size.required}")
        @Min(value = 1, message = "{po.line.size.range}")
        @Max(value = 10000, message = "{po.line.size.range}")
        private Integer heightMm;

        @NotNull(message = "{po.line.quantity.required}")
        @Min(value = 1, message = "{po.line.quantity.range}")
        @Max(value = 100000, message = "{po.line.quantity.range}")
        private Integer quantity;

        @NotNull(message = "{po.line.price.required}")
        @DecimalMin(value = "0", inclusive = false, message = "{po.line.price.positive}")
        @Digits(integer = 14, fraction = 4, message = "{po.line.price.digits}")
        private BigDecimal pricePerM2;

        /** A row left empty on the form: dropped before validation. */
        public boolean isBlank() {
            return productId == null && widthMm == null && heightMm == null && quantity == null && pricePerM2 == null;
        }
    }
}
