package com.ntaganira.heritier.iWarehouse.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.dto
 * - File      : WriteOffDto.java
 * - Date      : 2026. 10. 15.
 * - User      : Hntaganira
 * - Desc      : The receivable write-off form (F-14): the amount (at most what the customer owes), the invoice it is
 *               mainly about, the reason and the recovery tried.
 * </pre>
 */
@Getter
@Setter
@NoArgsConstructor
public class WriteOffDto {

    @NotNull(message = "{writeOff.amount.invalid}")
    @DecimalMin(value = "0.01", message = "{writeOff.amount.invalid}")
    @Digits(integer = 16, fraction = 2, message = "{writeOff.amount.invalid}")
    private BigDecimal amount;

    private UUID invoiceId;

    @NotBlank(message = "{writeOff.reason.required}")
    @Size(max = 255, message = "{writeOff.reason.size}")
    private String reason;

    @Size(max = 500, message = "{writeOff.recovery.size}")
    private String recoveryAction;
}
