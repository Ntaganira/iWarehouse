package com.ntaganira.heritier.iWarehouse.dto;

import com.ntaganira.heritier.iWarehouse.enums.AccountKey;
import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.dto
 * - File      : FloatClearanceDto.java
 * - Date      : 2026. 10. 11.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : The accountant's count of a trip's cash (ACC-06): what was counted, where it went (vault or bank, a bank
 *               deposit with its slip), a note.
 * </pre>
 */
@Getter
@Setter
@NoArgsConstructor
public class FloatClearanceDto {

    @NotNull(message = "{float.counted.required}")
    @DecimalMin(value = "0", message = "{float.counted.range}")
    @Digits(integer = 14, fraction = 2, message = "{float.counted.range}")
    private BigDecimal counted;

    @NotNull(message = "{float.account.required}")
    private AccountKey depositAccount = AccountKey.CASH_VAULT;

    @Size(max = 60, message = "{float.reference.size}")
    private String reference;

    @Size(max = 255, message = "{float.note.size}")
    private String note;
}
