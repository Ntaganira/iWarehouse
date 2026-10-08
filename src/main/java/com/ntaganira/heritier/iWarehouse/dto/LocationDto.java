package com.ntaganira.heritier.iWarehouse.dto;

import com.ntaganira.heritier.iWarehouse.enums.LocationType;
import com.ntaganira.heritier.iWarehouse.enums.RackOrientation;
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
 * - File      : LocationDto.java
 * - Date      : 2026. 10. 07.
 * - User      : Hntaganira
 * - Desc      : Add/edit location form (MD-02, MD-03). The type follows from the parent (none = site),
 *               so it is shown, not chosen. Limits and orientation only apply to racks.
 * </pre>
 */
@Getter
@Setter
@NoArgsConstructor
public class LocationDto {

    private UUID id;

    private UUID parentId;

    /** Shown on the form; LocationService derives the real type from the parent. */
    private LocationType type;

    @NotBlank(message = "{location.code.required}")
    @Pattern(regexp = "^[A-Z0-9][A-Z0-9-]{0,29}$", message = "{location.code.pattern}")
    private String code;

    @Size(max = 60, message = "{location.name.size}")
    private String name;

    private boolean offcut;

    @Min(value = 1, message = "{location.maxWeight.range}")
    @Max(value = 1_000_000, message = "{location.maxWeight.range}")
    private Integer maxWeightKg;

    @Min(value = 1, message = "{location.maxPieces.range}")
    @Max(value = 100_000, message = "{location.maxPieces.range}")
    private Integer maxPieces;

    private RackOrientation orientation;

    public void setCode(String code) {
        this.code = code == null ? null : code.trim().toUpperCase(Locale.ROOT);
    }
}
