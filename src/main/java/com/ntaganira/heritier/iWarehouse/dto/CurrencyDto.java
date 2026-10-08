package com.ntaganira.heritier.iWarehouse.dto;

import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.Locale;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.dto
 * - File      : CurrencyDto.java
 * - Date      : 2026. 10. 07.
 * - User      : Hntaganira
 * - Desc      : Add/edit currency form (ACC-02). The ISO code is fixed after creation because rates
 *               and documents refer to it.
 * </pre>
 */
@Getter
@Setter
@NoArgsConstructor
public class CurrencyDto {

    private UUID id;

    @NotBlank(message = "{currency.code.required}")
    @Pattern(regexp = "^[A-Z]{3}$", message = "{currency.code.pattern}")
    private String code;

    @NotBlank(message = "{currency.name.required}")
    @Size(max = 60, message = "{currency.name.size}")
    private String name;

    @NotBlank(message = "{currency.symbol.required}")
    @Size(max = 8, message = "{currency.symbol.size}")
    private String symbol;

    @NotNull(message = "{currency.decimals.range}")
    @Min(value = 0, message = "{currency.decimals.range}")
    @Max(value = 4, message = "{currency.decimals.range}")
    private Integer decimals = 2;

    public void setCode(String code) {
        this.code = code == null ? null : code.trim().toUpperCase(Locale.ROOT);
    }
}
