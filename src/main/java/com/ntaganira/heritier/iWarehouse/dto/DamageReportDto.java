package com.ntaganira.heritier.iWarehouse.dto;

import com.ntaganira.heritier.iWarehouse.enums.DamageCause;
import com.ntaganira.heritier.iWarehouse.enums.RecoveryFrom;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
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
 * - File      : DamageReportDto.java
 * - Date      : 2026. 10. 16.
 * - User      : Hntaganira
 * - Desc      : The damage report form (F-07, DMG-009): where and when, the cause, what happened, who was there, whether it
 *               can be repaired, the labels of the units damaged, and who may pay the loss back.
 * </pre>
 */
@Getter
@Setter
@NoArgsConstructor
public class DamageReportDto {

    @NotNull(message = "{damage.location.required}")
    private UUID locationId;

    @NotNull(message = "{damage.occurredOn.required}")
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate occurredOn;

    @NotNull(message = "{damage.cause.required}")
    private DamageCause cause;

    @NotBlank(message = "{damage.description.required}")
    @Size(max = 500, message = "{damage.description.size}")
    private String description;

    @Size(max = 255, message = "{damage.people.size}")
    private String peoplePresent;

    private boolean repairable;

    @NotBlank(message = "{damage.codes.required}")
    @Size(max = 8000, message = "{damage.codes.size}")
    private String codes;

    private RecoveryFrom recoverFrom = RecoveryFrom.NONE;

    @Size(max = 150, message = "{damage.recoverParty.size}")
    private String recoverParty;

    private UUID recoverCustomerId;

    private UUID recoverSupplierId;

    private UUID recoverDriverId;

    @DecimalMin(value = "0", message = "{damage.recoverAmount.range}")
    @Digits(integer = 16, fraction = 2, message = "{damage.recoverAmount.range}")
    private BigDecimal recoverAmount;

    /** The credit note the units came back on (a customer's return held for inspection). */
    private UUID creditNoteId;
}
