package com.ntaganira.heritier.iWarehouse.dto;

import com.ntaganira.heritier.iWarehouse.enums.GlassType;
import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.dto
 * - File      : ProductDto.java
 * - Date      : 2026. 10. 07.
 * - User      : Hntaganira
 * - Desc      : Add/edit glass product form (MD-01). A blank code takes the suggested one (CLR-6).
 *               Type, colour/finish and thickness are only read when the product is added.
 * </pre>
 */
@Getter
@Setter
@NoArgsConstructor
public class ProductDto {

    private UUID id;

    @Pattern(regexp = "^$|^[A-Z0-9][A-Z0-9.-]{1,19}$", message = "{product.code.pattern}")
    private String code;

    @NotNull(message = "{product.glassType.required}")
    private GlassType glassType;

    @Size(max = 30, message = "{product.variant.size}")
    private String variant;

    @NotNull(message = "{product.thickness.required}")
    @DecimalMin(value = "1", message = "{product.thickness.range}")
    @DecimalMax(value = "50", message = "{product.thickness.range}")
    @Digits(integer = 2, fraction = 2, message = "{product.thickness.range}")
    private BigDecimal thicknessMm;

    @NotNull(message = "{product.taxCategory.required}")
    private UUID taxCategoryId;

    @DecimalMin(value = "0", message = "{product.reorder.range}")
    @Digits(integer = 6, fraction = 4, message = "{product.reorder.range}")
    private BigDecimal reorderLevelM2;

    @Size(max = 255, message = "{product.notes.size}")
    private String notes;

    public void setCode(String code) {
        this.code = code == null ? null : code.trim().toUpperCase(Locale.ROOT);
    }
}
