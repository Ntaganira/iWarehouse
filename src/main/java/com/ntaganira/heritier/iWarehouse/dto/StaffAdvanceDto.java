package com.ntaganira.heritier.iWarehouse.dto;

import com.ntaganira.heritier.iWarehouse.enums.PerDiemCategory;
import com.ntaganira.heritier.iWarehouse.enums.StaffAdvanceKind;
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
 * - File      : StaffAdvanceDto.java
 * - Date      : 2026. 10. 16.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : The staff advance form (CMP 08): the employee, a journey (its per diem category, destination and days, plus
 *               any fares) or another purpose with its amount, and the cost centre.
 * </pre>
 */
@Getter
@Setter
@NoArgsConstructor
public class StaffAdvanceDto {

    @NotNull(message = "{advance.employee.required}")
    private UUID employeeId;

    @NotNull(message = "{advance.kind.required}")
    private StaffAdvanceKind kind = StaffAdvanceKind.TRAVEL;

    @NotBlank(message = "{advance.purpose.required}")
    @Size(max = 255, message = "{advance.purpose.size}")
    private String purpose;

    @NotNull(message = "{expense.line.costCentre.required}")
    private UUID costCentreId;

    private PerDiemCategory perDiemCategory;

    @Size(max = 100, message = "{advance.destination.size}")
    private String destination;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate departOn;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate returnOn;

    /** A journey's fares and fees on top of its per diem (RWF). */
    @DecimalMin(value = "0", message = "{advance.extra.negative}")
    @Digits(integer = 16, fraction = 2, message = "{advance.amount.required}")
    private BigDecimal extraAmount;

    /** Another advance's amount (RWF). */
    @Digits(integer = 16, fraction = 2, message = "{advance.amount.required}")
    private BigDecimal amount;
}
