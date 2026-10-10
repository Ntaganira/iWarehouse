package com.ntaganira.heritier.iWarehouse.dto;

import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;
import java.util.Locale;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.dto
 * - File      : VehicleDto.java
 * - Date      : 2026. 10. 10.
 * - User      : Hntaganira
 * - Desc      : Add/edit vehicle form (FLT-01). The plate is kept upper case without spaces ("rac 123 a" is RAC123A).
 * </pre>
 */
@Getter
@Setter
@NoArgsConstructor
public class VehicleDto {

    private UUID id;

    @NotBlank(message = "{vehicle.plate.required}")
    @Pattern(regexp = "^$|^[A-Z0-9]{2,15}$", message = "{vehicle.plate.pattern}")
    private String plate;

    @NotBlank(message = "{vehicle.model.required}")
    @Size(max = 60, message = "{vehicle.model.size}")
    private String model;

    @Size(max = 255, message = "{vehicle.racks.size}")
    private String rackConfiguration;

    @NotNull(message = "{vehicle.maxLoad.required}")
    @Min(value = 1, message = "{vehicle.maxLoad.range}")
    @Max(value = 60000, message = "{vehicle.maxLoad.range}")
    private Integer maxLoadKg;

    @NotNull(message = "{vehicle.maxPieces.required}")
    @Min(value = 1, message = "{vehicle.maxPieces.range}")
    @Max(value = 2000, message = "{vehicle.maxPieces.range}")
    private Integer maxPieces;

    @NotNull(message = "{vehicle.insurance.required}")
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate insuranceExpiry;

    @NotNull(message = "{vehicle.inspection.required}")
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate inspectionExpiry;

    @Size(max = 255, message = "{vehicle.notes.size}")
    private String notes;

    public void setPlate(String plate) {
        this.plate = plate == null ? null : plate.replaceAll("[\\s-]+", "").toUpperCase(Locale.ROOT);
    }
}
