package com.ntaganira.heritier.iWarehouse.dto;

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
 * - File      : TaxCategoryDto.java
 * - Date      : 2026. 10. 07.
 * - User      : Hntaganira
 * - Desc      : Add/edit tax category form (TAX-01). The code is fixed after creation because products
 *               and invoices refer to the category.
 * </pre>
 */
@Getter
@Setter
@NoArgsConstructor
public class TaxCategoryDto {

    private UUID id;

    @NotBlank(message = "{tax.code.required}")
    @Pattern(regexp = "^[A-Z][A-Z0-9_]{1,19}$", message = "{tax.code.pattern}")
    private String code;

    @NotBlank(message = "{tax.name.required}")
    @Size(max = 100, message = "{tax.name.size}")
    private String name;

    @NotNull(message = "{tax.rate.required}")
    @DecimalMin(value = "0", message = "{tax.rate.range}")
    @DecimalMax(value = "100", message = "{tax.rate.range}")
    @Digits(integer = 3, fraction = 2, message = "{tax.rate.digits}")
    private BigDecimal rate;

    @NotBlank(message = "{tax.ebmCode.required}")
    @Pattern(regexp = "^[A-Z]$", message = "{tax.ebmCode.pattern}")
    private String ebmCode;

    @Size(max = 255, message = "{tax.description.size}")
    private String description;

    private boolean defaultCategory;

    public void setCode(String code) {
        this.code = code == null ? null : code.trim().toUpperCase(Locale.ROOT);
    }

    public void setEbmCode(String ebmCode) {
        this.ebmCode = ebmCode == null ? null : ebmCode.trim().toUpperCase(Locale.ROOT);
    }
}
