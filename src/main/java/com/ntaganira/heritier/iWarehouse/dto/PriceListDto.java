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
 * - File      : PriceListDto.java
 * - Date      : 2026. 10. 08.
 * - User      : Hntaganira
 * - Desc      : Add/edit price list form (MD-06): name, whether prices include VAT, and an optional
 *               minimum chargeable area. Prices themselves are edited on their own page.
 * </pre>
 */
@Getter
@Setter
@NoArgsConstructor
public class PriceListDto {

    private UUID id;

    @NotBlank(message = "{pricelist.code.required}")
    @Pattern(regexp = "^[A-Z0-9][A-Z0-9_-]{1,19}$", message = "{pricelist.code.pattern}")
    private String code;

    @NotBlank(message = "{pricelist.name.required}")
    @Size(max = 60, message = "{pricelist.name.size}")
    private String name;

    private boolean pricesIncludeVat = true;

    @DecimalMin(value = "0.0001", message = "{pricelist.minArea.range}")
    @DecimalMax(value = "100", message = "{pricelist.minArea.range}")
    @Digits(integer = 3, fraction = 4, message = "{pricelist.minArea.range}")
    private BigDecimal minChargeableM2;

    @Size(max = 255, message = "{party.notes.size}")
    private String notes;

    public void setCode(String code) {
        this.code = code == null ? null : code.trim().toUpperCase(Locale.ROOT);
    }
}
