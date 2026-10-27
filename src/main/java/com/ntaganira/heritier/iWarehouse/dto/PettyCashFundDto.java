package com.ntaganira.heritier.iWarehouse.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.dto
 * - File      : PettyCashFundDto.java
 * - Date      : 2026. 10. 16.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : The petty cash fund form (CMP 07): its site (fixed once opened), its custodian and its float.
 * </pre>
 */
@Getter
@Setter
@NoArgsConstructor
public class PettyCashFundDto {

    @NotNull(message = "{pettyCash.site.required}")
    private UUID locationId;

    @NotNull(message = "{pettyCash.custodian.required}")
    private Long custodianUserId;

    @NotNull(message = "{pettyCash.imprest.required}")
    @DecimalMin(value = "1", message = "{pettyCash.imprest.required}")
    @Digits(integer = 16, fraction = 2, message = "{pettyCash.imprest.required}")
    private BigDecimal imprest;
}
