package com.ntaganira.heritier.iWarehouse.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
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
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
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

    /** The load request (F-09): route, towns, days out, the day the trip is settled by. */
    @Size(max = 200, message = "{trip.route.size}")
    private String route;

    @Size(max = 300, message = "{trip.towns.size}")
    private String towns;

    @Min(value = 1, message = "{trip.daysOut.range}")
    @Max(value = 60, message = "{trip.daysOut.range}")
    private Integer daysOut;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate settleBy;
}
