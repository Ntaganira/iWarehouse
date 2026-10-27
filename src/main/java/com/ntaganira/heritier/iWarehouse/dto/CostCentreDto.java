package com.ntaganira.heritier.iWarehouse.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.dto
 * - File      : CostCentreDto.java
 * - Date      : 2026. 10. 16.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : The cost centre form (F-15 A): its code, name and manager.
 * </pre>
 */
@Getter
@Setter
@NoArgsConstructor
public class CostCentreDto {

    @NotBlank(message = "{costCentre.code.required}")
    @Pattern(regexp = "^[A-Za-z0-9-]{2,10}$", message = "{costCentre.code.pattern}")
    private String code;

    @NotBlank(message = "{costCentre.name.required}")
    @Size(max = 100, message = "{costCentre.name.size}")
    private String name;

    private Long managerUserId;

    private boolean enabled = true;
}
