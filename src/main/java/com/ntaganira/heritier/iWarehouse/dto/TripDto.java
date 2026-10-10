package com.ntaganira.heritier.iWarehouse.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.dto
 * - File      : TripDto.java
 * - Date      : 2026. 10. 10.
 * - User      : Hntaganira
 * - Desc      : Plan/edit trip form (FLT-05): vehicle, driver, date and the route or area. The manifest is built on the
 *               trip's page.
 * </pre>
 */
@Getter
@Setter
@NoArgsConstructor
public class TripDto {

    private UUID id;

    @NotNull(message = "{trip.vehicle.required}")
    private UUID vehicleId;

    @NotNull(message = "{trip.driver.required}")
    private UUID driverId;

    @NotNull(message = "{trip.date.required}")
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate tripDate;

    @NotBlank(message = "{trip.area.required}")
    @Size(max = 120, message = "{trip.area.size}")
    private String area;

    @Size(max = 255, message = "{trip.note.size}")
    private String note;
}
