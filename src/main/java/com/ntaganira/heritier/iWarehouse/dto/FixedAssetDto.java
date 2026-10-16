package com.ntaganira.heritier.iWarehouse.dto;

import com.ntaganira.heritier.iWarehouse.enums.FixedAssetCategory;
import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.format.annotation.DateTimeFormat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.dto
 * - File      : FixedAssetDto.java
 * - Date      : 2026. 10. 16.
 * - User      : Hntaganira
 * - Desc      : The fixed asset form (C.08): what it is, its category, cost centre and place, serial number, the day it was
 *               acquired, its cost and residual value, useful life, the month it is depreciated from and, for an asset registered
 *               at its value to date, the depreciation before that month. An asset from an expense bill keeps the bill's cost.
 * </pre>
 */
@Getter
@Setter
@NoArgsConstructor
public class FixedAssetDto {

    @NotBlank(message = "{asset.name.required}")
    @Size(max = 150, message = "{asset.name.size}")
    private String name;

    @NotNull(message = "{asset.category.required}")
    private FixedAssetCategory category;

    private UUID costCentreId;

    private UUID locationId;

    @Size(max = 60, message = "{asset.serial.size}")
    private String serialNumber;

    @NotNull(message = "{asset.acquiredOn.required}")
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate acquiredOn;

    @NotNull(message = "{asset.cost.required}")
    @DecimalMin(value = "0.01", message = "{asset.cost.range}")
    @Digits(integer = 16, fraction = 2, message = "{asset.cost.range}")
    private BigDecimal cost;

    @DecimalMin(value = "0", message = "{asset.residual.range}")
    @Digits(integer = 16, fraction = 2, message = "{asset.residual.range}")
    private BigDecimal residualValue;

    @NotNull(message = "{asset.life.required}")
    @Min(value = 1, message = "{asset.life.range}")
    @Max(value = 1200, message = "{asset.life.range}")
    private Integer usefulLifeMonths;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate depreciateFrom;

    @DecimalMin(value = "0", message = "{asset.opening.range}")
    @Digits(integer = 16, fraction = 2, message = "{asset.opening.range}")
    private BigDecimal openingAccumulated;
}
