package com.ntaganira.heritier.iWarehouse.dto;

import com.ntaganira.heritier.iWarehouse.enums.CustomerType;
import com.ntaganira.heritier.iWarehouse.service.PartyRules;
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
 * - File      : CustomerDto.java
 * - Date      : 2026. 10. 08.
 * - User      : Hntaganira
 * - Desc      : Add/edit customer form (MD-04). Credit limit, payment terms and price list are only
 *               applied for users with PERM_MANAGE_CUSTOMER_TERMS; the code is given on saving.
 * </pre>
 */
@Getter
@Setter
@NoArgsConstructor
public class CustomerDto {

    private UUID id;

    @NotNull(message = "{customer.type.required}")
    private CustomerType type = CustomerType.ACCOUNT;

    @NotBlank(message = "{customer.name.required}")
    @Size(max = 120, message = "{customer.name.size}")
    private String name;

    /** Spaces and dashes are dropped as typed (see setter): 9 digits remain for a Rwandan TIN. */
    @Pattern(regexp = "^(\\d{9})?$", message = "{customer.tin.pattern}")
    private String tin;

    @Pattern(regexp = "^[0-9+()\\s-]{0,30}$", message = "{party.phone.pattern}")
    private String phone;

    @Email(message = "{party.email.invalid}")
    @Size(max = 120, message = "{party.email.invalid}")
    private String email;

    @Size(max = 100, message = "{party.contact.size}")
    private String contactName;

    @Size(max = 255, message = "{party.address.size}")
    private String address;

    @NotNull(message = "{customer.credit.range}")
    @DecimalMin(value = "0", message = "{customer.credit.range}")
    @Digits(integer = 16, fraction = 0, message = "{customer.credit.range}")
    private BigDecimal creditLimit = BigDecimal.ZERO;

    @NotNull(message = "{party.terms.range}")
    @Min(value = 0, message = "{party.terms.range}")
    @Max(value = 365, message = "{party.terms.range}")
    private Integer paymentTermsDays = 0;

    /** Null = the default price list. */
    private UUID priceListId;

    @Size(max = 255, message = "{party.notes.size}")
    private String notes;

    public void setTin(String tin) {
        String normalized = PartyRules.normalizeTin(tin);
        this.tin = normalized == null ? "" : normalized;
    }
}
