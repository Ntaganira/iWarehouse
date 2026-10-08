package com.ntaganira.heritier.iWarehouse.dto;

import com.ntaganira.heritier.iWarehouse.enums.ChargeUnit;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.Locale;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.dto
 * - File      : ProcessingServiceDto.java
 * - Date      : 2026. 10. 08.
 * - User      : Hntaganira
 * - Desc      : Add/edit processing service form (MD-06). The code is fixed after creation.
 * </pre>
 */
@Getter
@Setter
@NoArgsConstructor
public class ProcessingServiceDto {

    private UUID id;

    @NotBlank(message = "{service.code.required}")
    @Pattern(regexp = "^[A-Z0-9][A-Z0-9_-]{1,19}$", message = "{pricelist.code.pattern}")
    private String code;

    @NotBlank(message = "{service.name.required}")
    @Size(max = 60, message = "{service.name.size}")
    private String name;

    @NotNull(message = "{service.unit.required}")
    private ChargeUnit chargeUnit;

    public void setCode(String code) {
        this.code = code == null ? null : code.trim().toUpperCase(Locale.ROOT);
    }
}
