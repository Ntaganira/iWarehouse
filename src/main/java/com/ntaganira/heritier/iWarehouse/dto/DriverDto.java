package com.ntaganira.heritier.iWarehouse.dto;

import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;
import java.util.Locale;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.dto
 * - File      : DriverDto.java
 * - Date      : 2026. 10. 10.
 * - User      : Hntaganira
 * - Desc      : Register/edit driver form (FLT-03). The user is chosen once. The national ID keeps its 16 digits (spaces
 *               dropped); the licence number and category are kept upper case.
 * </pre>
 */
@Getter
@Setter
@NoArgsConstructor
public class DriverDto {

    private UUID id;

    @NotNull(message = "{driver.user.required}")
    private Long userId;

    @NotBlank(message = "{driver.nationalId.required}")
    @Pattern(regexp = "^$|^[0-9]{16}$", message = "{driver.nationalId.pattern}")
    private String nationalId;

    @NotBlank(message = "{driver.licence.required}")
    @Pattern(regexp = "^$|^[A-Z0-9/-]{3,30}$", message = "{driver.licence.pattern}")
    private String licenceNumber;

    @NotBlank(message = "{driver.category.required}")
    @Pattern(regexp = "^$|^[A-Z0-9, ]{1,20}$", message = "{driver.category.pattern}")
    private String licenceCategory;

    @NotNull(message = "{driver.expiry.required}")
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate licenceExpiry;

    private UUID defaultVehicleId;

    public void setNationalId(String nationalId) {
        this.nationalId = nationalId == null ? null : nationalId.replaceAll("\\s+", "");
    }

    public void setLicenceNumber(String licenceNumber) {
        this.licenceNumber = licenceNumber == null ? null : licenceNumber.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
    }

    public void setLicenceCategory(String licenceCategory) {
        this.licenceCategory = licenceCategory == null ? null
                : licenceCategory.trim().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
    }
}
