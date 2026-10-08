package com.ntaganira.heritier.iWarehouse.dto;

import com.ntaganira.heritier.iWarehouse.enums.Incoterm;
import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.Locale;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.dto
 * - File      : SupplierDto.java
 * - Date      : 2026. 10. 08.
 * - User      : Hntaganira
 * - Desc      : Add/edit supplier form (MD-05). The code is given on saving and never typed.
 * </pre>
 */
@Getter
@Setter
@NoArgsConstructor
public class SupplierDto {

    private UUID id;

    @NotBlank(message = "{supplier.name.required}")
    @Size(max = 120, message = "{supplier.name.size}")
    private String name;

    @NotBlank(message = "{supplier.country.required}")
    @Pattern(regexp = "^[A-Z]{2}$", message = "{supplier.country.required}")
    private String countryCode;

    @NotBlank(message = "{supplier.currency.required}")
    private String currencyCode;

    private Incoterm incoterm;

    @Size(max = 30, message = "{supplier.tin.size}")
    private String tin;

    @NotNull(message = "{party.terms.range}")
    @Min(value = 0, message = "{party.terms.range}")
    @Max(value = 365, message = "{party.terms.range}")
    private Integer paymentTermsDays = 0;

    @Size(max = 100, message = "{party.contact.size}")
    private String contactName;

    @Pattern(regexp = "^[0-9+()\\s-]{0,30}$", message = "{party.phone.pattern}")
    private String phone;

    @Email(message = "{party.email.invalid}")
    @Size(max = 120, message = "{party.email.invalid}")
    private String email;

    @Size(max = 255, message = "{party.address.size}")
    private String address;

    @Size(max = 255, message = "{party.notes.size}")
    private String notes;

    public void setCountryCode(String countryCode) {
        this.countryCode = countryCode == null ? null : countryCode.trim().toUpperCase(Locale.ROOT);
    }

    public void setCurrencyCode(String currencyCode) {
        this.currencyCode = currencyCode == null ? null : currencyCode.trim().toUpperCase(Locale.ROOT);
    }
}
