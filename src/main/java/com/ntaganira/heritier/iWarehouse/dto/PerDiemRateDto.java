package com.ntaganira.heritier.iWarehouse.dto;

import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.dto
 * - File      : PerDiemRateDto.java
 * - Date      : 2026. 10. 16.
 * - User      : Hntaganira
 * - Desc      : A per diem rate (CMP 08): its currency, the meals and incidentals of a day, the accommodation of a night.
 * </pre>
 */
@Getter
@Setter
@NoArgsConstructor
public class PerDiemRateDto {

    @NotBlank(message = "{perDiem.currency.required}")
    private String currencyCode;

    @NotNull(message = "{perDiem.amount.required}")
    @DecimalMin(value = "0", message = "{perDiem.amount.required}")
    @Digits(integer = 16, fraction = 2, message = "{perDiem.amount.required}")
    private BigDecimal meal;

    @NotNull(message = "{perDiem.amount.required}")
    @DecimalMin(value = "0", message = "{perDiem.amount.required}")
    @Digits(integer = 16, fraction = 2, message = "{perDiem.amount.required}")
    private BigDecimal accommodation;

    @NotNull(message = "{perDiem.amount.required}")
    @DecimalMin(value = "0", message = "{perDiem.amount.required}")
    @Digits(integer = 16, fraction = 2, message = "{perDiem.amount.required}")
    private BigDecimal incidentals;
}
