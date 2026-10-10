package com.ntaganira.heritier.iWarehouse.dto;

import com.ntaganira.heritier.iWarehouse.enums.EbmMode;
import com.ntaganira.heritier.iWarehouse.enums.RateSource;
import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.Locale;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.dto
 * - File      : SettingsDto.java
 * - Date      : 2026. 10. 07.
 * - User      : Hntaganira
 * - Desc      : Settings form (ADM-03). One property per SettingKey, matched by name; the limits here
 *               are the only validation the values get, so keep them in step with the SRS.
 * </pre>
 */
@Getter
@Setter
@NoArgsConstructor
public class SettingsDto {

    // --- Company (printed on invoices and receipts) ---

    @NotBlank(message = "{settings.companyName.required}")
    @Size(max = 150, message = "{settings.companyName.size}")
    private String companyName;

    /** Rwanda TIN: 9 digits. */
    @Pattern(regexp = "^$|^[0-9]{9}$", message = "{settings.companyTin.pattern}")
    private String companyTin;

    @Size(max = 255, message = "{settings.companyAddress.size}")
    private String companyAddress;

    @Pattern(regexp = "^$|^[+0-9 ()-]{6,20}$", message = "{settings.companyPhone.pattern}")
    private String companyPhone;

    @Email(message = "{settings.companyEmail.invalid}")
    @Size(max = 100, message = "{settings.companyEmail.size}")
    private String companyEmail;

    @NotBlank(message = "{settings.branchCode.required}")
    @Pattern(regexp = "^[A-Z0-9]{1,10}$", message = "{settings.branchCode.pattern}")
    private String branchCode;

    // --- Production (PRD-04) ---

    @NotNull(message = "{settings.value.required}")
    @DecimalMin(value = "0.01", message = "{settings.offcutMinArea.range}")
    @DecimalMax(value = "10", message = "{settings.offcutMinArea.range}")
    @Digits(integer = 2, fraction = 4, message = "{settings.area.digits}")
    private BigDecimal offcutMinArea;

    @NotNull(message = "{settings.value.required}")
    @Min(value = 50, message = "{settings.offcutMinSide.range}")
    @Max(value = 3000, message = "{settings.offcutMinSide.range}")
    private Integer offcutMinSide;

    @NotNull(message = "{settings.value.required}")
    @DecimalMin(value = "1", message = "{settings.glassDensity.range}")
    @DecimalMax(value = "5", message = "{settings.glassDensity.range}")
    @Digits(integer = 1, fraction = 3, message = "{settings.glassDensity.digits}")
    private BigDecimal glassDensity;

    // --- Pricing (MD-06) ---

    @NotNull(message = "{settings.value.required}")
    @DecimalMin(value = "0", message = "{settings.minChargeableArea.range}")
    @DecimalMax(value = "10", message = "{settings.minChargeableArea.range}")
    @Digits(integer = 2, fraction = 4, message = "{settings.area.digits}")
    private BigDecimal minChargeableArea;

    @NotNull(message = "{settings.value.required}")
    @Min(value = 7, message = "{settings.slowMovingDays.range}")
    @Max(value = 730, message = "{settings.slowMovingDays.range}")
    private Integer slowMovingDays;

    @NotNull(message = "{settings.value.required}")
    @Min(value = 1, message = "{settings.quotationValidityDays.range}")
    @Max(value = 365, message = "{settings.quotationValidityDays.range}")
    private Integer quotationValidityDays;

    @NotNull(message = "{settings.value.required}")
    @DecimalMin(value = "0", message = "{settings.depositMinPercent.range}")
    @DecimalMax(value = "100", message = "{settings.depositMinPercent.range}")
    @Digits(integer = 3, fraction = 2, message = "{settings.percent.digits}")
    private BigDecimal depositMinPercent;

    // --- Approvals (INV-07, POS-06) ---

    /** RWF, no decimals. */
    @NotNull(message = "{settings.value.required}")
    @DecimalMin(value = "0", message = "{settings.amount.range}")
    @Digits(integer = 16, fraction = 0, message = "{settings.amount.digits}")
    private BigDecimal adjustmentApprovalLimit;

    @NotNull(message = "{settings.value.required}")
    @DecimalMin(value = "0", message = "{settings.discountApprovalPercent.range}")
    @DecimalMax(value = "100", message = "{settings.discountApprovalPercent.range}")
    @Digits(integer = 3, fraction = 2, message = "{settings.percent.digits}")
    private BigDecimal discountApprovalPercent;

    // --- Currency (ACC-02) ---

    @NotNull(message = "{settings.value.required}")
    private RateSource defaultRateSource;

    @NotNull(message = "{settings.value.required}")
    @Min(value = 1, message = "{settings.maxRateAgeDays.range}")
    @Max(value = 90, message = "{settings.maxRateAgeDays.range}")
    private Integer maxRateAgeDays;

    // --- Alerts (RPT-06) ---

    @NotNull(message = "{settings.value.required}")
    private Boolean alertEmail;

    // --- EBM fiscal signing (TAX-02) ---

    @NotNull(message = "{settings.value.required}")
    private EbmMode ebmMode;

    @Pattern(regexp = "^$|^https?://[^\\s]{3,240}$", message = "{settings.ebmVsdcUrl.pattern}")
    private String ebmVsdcUrl;

    @NotBlank(message = "{settings.value.required}")
    @Pattern(regexp = "^[0-9]{2}$", message = "{settings.ebmBranchId.pattern}")
    private String ebmBranchId;

    @Size(max = 100, message = "{settings.ebmDeviceSerial.size}")
    private String ebmDeviceSerial;

    @Pattern(regexp = "^$|^[0-9A-Za-z]{1,10}$", message = "{settings.ebmItemClass.pattern}")
    private String ebmGlassItemClass;

    @Pattern(regexp = "^$|^[0-9A-Za-z]{1,10}$", message = "{settings.ebmItemClass.pattern}")
    private String ebmServiceItemClass;

    @NotBlank(message = "{settings.value.required}")
    @Pattern(regexp = "^[A-Z]{2}$", message = "{settings.ebmOriginCountry.pattern}")
    private String ebmOriginCountry;

    @NotBlank(message = "{settings.value.required}")
    @Pattern(regexp = "^https://[^\\s]{3,240}$", message = "{settings.ebmReceiptUrl.pattern}")
    private String ebmReceiptUrl;

    public void setBranchCode(String branchCode) {
        this.branchCode = branchCode == null ? null : branchCode.trim().toUpperCase(Locale.ROOT);
    }

    public void setEbmOriginCountry(String ebmOriginCountry) {
        this.ebmOriginCountry = ebmOriginCountry == null ? null : ebmOriginCountry.trim().toUpperCase(Locale.ROOT);
    }

    public void setCompanyTin(String companyTin) {
        this.companyTin = companyTin == null ? null : companyTin.replace(" ", "");
    }
}
