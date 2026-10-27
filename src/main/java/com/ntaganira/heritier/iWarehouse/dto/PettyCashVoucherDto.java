package com.ntaganira.heritier.iWarehouse.dto;

import com.ntaganira.heritier.iWarehouse.enums.ExpenseCategory;
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
 * - File      : PettyCashVoucherDto.java
 * - Date      : 2026. 10. 16.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : The petty cash voucher form (CMP 07, F-13 D): the fund, the day, who was paid and for what, the category, the
 *               cost centre, the amount and the receipt's number.
 * </pre>
 */
@Getter
@Setter
@NoArgsConstructor
public class PettyCashVoucherDto {

    @NotNull(message = "{pettyCash.fund.required}")
    private UUID fundId;

    @NotNull(message = "{pettyCash.voucher.date.required}")
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate voucherDate;

    @NotBlank(message = "{pettyCash.voucher.payee.required}")
    @Size(max = 100, message = "{pettyCash.voucher.payee.size}")
    private String payee;

    @NotBlank(message = "{pettyCash.voucher.purpose.required}")
    @Size(max = 255, message = "{pettyCash.voucher.purpose.size}")
    private String purpose;

    @NotNull(message = "{pettyCash.voucher.category}")
    private ExpenseCategory category;

    @NotNull(message = "{expense.line.costCentre.required}")
    private UUID costCentreId;

    @NotNull(message = "{pettyCash.amount.required}")
    @DecimalMin(value = "0.01", message = "{pettyCash.amount.required}")
    @Digits(integer = 16, fraction = 2, message = "{pettyCash.amount.required}")
    private BigDecimal amount;

    @Size(max = 60, message = "{pettyCash.voucher.receiptRef.size}")
    private String receiptRef;
}
