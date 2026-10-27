package com.ntaganira.heritier.iWarehouse.dto;

import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.dto
 * - File      : ServicePlanDto.java
 * - Date      : 2026. 10. 12.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : A vehicle's service plan as entered: its name, every how many km and/or months, when it was last done and
 *               at how many km, a note.
 * </pre>
 */
@Getter
@Setter
@NoArgsConstructor
public class ServicePlanDto {

    @NotBlank(message = "{servicePlan.name.required}")
    @Size(max = 60, message = "{servicePlan.name.size}")
    private String name;

    @Min(value = 100, message = "{servicePlan.km.range}")
    @Max(value = 200_000, message = "{servicePlan.km.range}")
    private Integer everyKm;

    @Min(value = 1, message = "{servicePlan.months.range}")
    @Max(value = 60, message = "{servicePlan.months.range}")
    private Integer everyMonths;

    @NotNull(message = "{servicePlan.lastOn.required}")
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate lastDoneOn;

    @Min(value = 0, message = "{servicePlan.lastKm.range}")
    @Max(value = 9_999_999, message = "{servicePlan.lastKm.range}")
    private Integer lastDoneKm;

    @Size(max = 255, message = "{servicePlan.note.size}")
    private String note;
}
