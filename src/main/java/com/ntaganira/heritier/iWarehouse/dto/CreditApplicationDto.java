package com.ntaganira.heritier.iWarehouse.dto;

import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.dto
 * - File      : CreditApplicationDto.java
 * - Date      : 2026. 10. 15.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : The credit application form (F-02 C): the credit limit, payment terms and price list wanted, and why.
 * </pre>
 */
@Getter
@Setter
@NoArgsConstructor
public class CreditApplicationDto {

    @NotNull(message = "{customer.credit.range}")
    @DecimalMin(value = "0", message = "{customer.credit.range}")
    @Digits(integer = 16, fraction = 0, message = "{customer.credit.range}")
    private BigDecimal creditLimit;

    @NotNull(message = "{party.terms.range}")
    @Min(value = 0, message = "{party.terms.range}")
    @Max(value = 365, message = "{party.terms.range}")
    private Integer paymentTermsDays;

    /** Null = the default price list. */
    private UUID priceListId;

    @NotBlank(message = "{creditApp.reason.required}")
    @Size(max = 500, message = "{creditApp.reason.size}")
    private String reason;
}
