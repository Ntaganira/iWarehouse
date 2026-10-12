package com.ntaganira.heritier.iWarehouse.dto;

import com.ntaganira.heritier.iWarehouse.enums.VehicleCostCategory;
import com.ntaganira.heritier.iWarehouse.enums.VehicleCostPayment;
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
 * - File      : VehicleCostDto.java
 * - Date      : 2026. 10. 12.
 * - User      : Hntaganira
 * - Desc      : A vehicle cost as recorded: the vehicle (and trip), day, what it was for, odometer and litres, the amount
 *               with its VAT, how it was paid (with the reference, the garage and its invoice number) and the service
 *               plan it does. The rules that join fields are checked by VehicleCostService.
 * </pre>
 */
@Getter
@Setter
@NoArgsConstructor
public class VehicleCostDto {

    @NotNull(message = "{vehicleCost.vehicle.required}")
    private UUID vehicleId;

    private UUID tripId;

    @NotNull(message = "{vehicleCost.date.required}")
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate costDate;

    @NotNull(message = "{vehicleCost.category.required}")
    private VehicleCostCategory category;

    @NotBlank(message = "{vehicleCost.description.required}")
    @Size(max = 255, message = "{vehicleCost.description.size}")
    private String description;

    @Min(value = 0, message = "{vehicleCost.odometer.range}")
    @Max(value = 9_999_999, message = "{vehicleCost.odometer.range}")
    private Integer odometerKm;

    @DecimalMin(value = "0.01", message = "{vehicleCost.litres.range}")
    @DecimalMax(value = "2000", message = "{vehicleCost.litres.range}")
    @Digits(integer = 4, fraction = 2, message = "{vehicleCost.litres.range}")
    private BigDecimal litres;

    @NotNull(message = "{vehicleCost.amount.required}")
    @DecimalMin(value = "1", message = "{vehicleCost.amount.range}")
    @Digits(integer = 12, fraction = 0, message = "{vehicleCost.amount.range}")
    private BigDecimal amount;

    @DecimalMin(value = "0", message = "{vehicleCost.vat.range}")
    @Digits(integer = 12, fraction = 0, message = "{vehicleCost.vat.range}")
    private BigDecimal vatAmount;

    @NotNull(message = "{vehicleCost.payment.required}")
    private VehicleCostPayment payment;

    @Size(max = 60, message = "{vehicleCost.reference.size}")
    private String paymentRef;

    private UUID supplierId;

    @Size(max = 60, message = "{vehicleCost.reference.size}")
    private String supplierRef;

    private UUID servicePlanId;
}
