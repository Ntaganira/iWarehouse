package com.ntaganira.heritier.iWarehouse.dto;

import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.format.annotation.DateTimeFormat;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.dto
 * - File      : TripFuelDto.java
 * - Date      : 2026. 10. 10.
 * - User      : Hntaganira
 * - Desc      : Fuel bought for a trip (FLT-12): day, litres, RWF, station, receipt.
 * </pre>
 */
@Getter
@Setter
@NoArgsConstructor
public class TripFuelDto {

    @NotNull(message = "{tripFuel.date.required}")
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate filledOn;

    @NotNull(message = "{tripFuel.litres.required}")
    @DecimalMin(value = "0.01", message = "{tripFuel.litres.range}")
    @DecimalMax(value = "2000", message = "{tripFuel.litres.range}")
    @Digits(integer = 4, fraction = 2, message = "{tripFuel.litres.range}")
    private BigDecimal litres;

    @NotNull(message = "{tripFuel.amount.required}")
    @DecimalMin(value = "0", message = "{tripFuel.amount.range}")
    @Digits(integer = 12, fraction = 0, message = "{tripFuel.amount.range}")
    private BigDecimal amount;

    @Size(max = 80, message = "{tripFuel.station.size}")
    private String station;

    @Size(max = 255, message = "{tripFuel.note.size}")
    private String note;
}
