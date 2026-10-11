package com.ntaganira.heritier.iWarehouse.dto;

import com.ntaganira.heritier.iWarehouse.entity.PayrollRateSet;
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
 * - File      : PayrollRateDto.java
 * - Date      : 2026. 10. 11.
 * - User      : Hntaganira
 * - Desc      : The payroll rates form: the date they apply from, the PAYE bands (whole RWF a month) and every rate as a
 *               percentage with up to 2 decimals.
 * </pre>
 */
@Getter
@Setter
@NoArgsConstructor
public class PayrollRateDto {

    private UUID id;

    @NotNull(message = "{payrollRate.from.required}")
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate effectiveFrom;

    @NotNull(message = "{payrollRate.band.required}")
    @DecimalMin(value = "0", message = "{payroll.amount.range}")
    @Digits(integer = 12, fraction = 0, message = "{payroll.amount.range}")
    private BigDecimal payeBand1;

    @NotNull(message = "{payrollRate.band.required}")
    @DecimalMin(value = "0", message = "{payroll.amount.range}")
    @Digits(integer = 12, fraction = 0, message = "{payroll.amount.range}")
    private BigDecimal payeBand2;

    @NotNull(message = "{payrollRate.band.required}")
    @DecimalMin(value = "0", message = "{payroll.amount.range}")
    @Digits(integer = 12, fraction = 0, message = "{payroll.amount.range}")
    private BigDecimal payeBand3;

    @NotNull(message = "{payrollRate.rate.required}")
    @DecimalMin(value = "0", message = "{payrollRate.rate.range}")
    @DecimalMax(value = "100", message = "{payrollRate.rate.range}")
    @Digits(integer = 3, fraction = 2, message = "{payrollRate.rate.range}")
    private BigDecimal payeRate1;

    @NotNull(message = "{payrollRate.rate.required}")
    @DecimalMin(value = "0", message = "{payrollRate.rate.range}")
    @DecimalMax(value = "100", message = "{payrollRate.rate.range}")
    @Digits(integer = 3, fraction = 2, message = "{payrollRate.rate.range}")
    private BigDecimal payeRate2;

    @NotNull(message = "{payrollRate.rate.required}")
    @DecimalMin(value = "0", message = "{payrollRate.rate.range}")
    @DecimalMax(value = "100", message = "{payrollRate.rate.range}")
    @Digits(integer = 3, fraction = 2, message = "{payrollRate.rate.range}")
    private BigDecimal payeRate3;

    @NotNull(message = "{payrollRate.rate.required}")
    @DecimalMin(value = "0", message = "{payrollRate.rate.range}")
    @DecimalMax(value = "100", message = "{payrollRate.rate.range}")
    @Digits(integer = 3, fraction = 2, message = "{payrollRate.rate.range}")
    private BigDecimal payeRate4;

    @NotNull(message = "{payrollRate.rate.required}")
    @DecimalMin(value = "0", message = "{payrollRate.rate.range}")
    @DecimalMax(value = "100", message = "{payrollRate.rate.range}")
    @Digits(integer = 3, fraction = 2, message = "{payrollRate.rate.range}")
    private BigDecimal payeSecondaryRate;

    @NotNull(message = "{payrollRate.rate.required}")
    @DecimalMin(value = "0", message = "{payrollRate.rate.range}")
    @DecimalMax(value = "100", message = "{payrollRate.rate.range}")
    @Digits(integer = 3, fraction = 2, message = "{payrollRate.rate.range}")
    private BigDecimal pensionEmployeeRate;

    @NotNull(message = "{payrollRate.rate.required}")
    @DecimalMin(value = "0", message = "{payrollRate.rate.range}")
    @DecimalMax(value = "100", message = "{payrollRate.rate.range}")
    @Digits(integer = 3, fraction = 2, message = "{payrollRate.rate.range}")
    private BigDecimal pensionEmployerRate;

    @NotNull(message = "{payrollRate.rate.required}")
    @DecimalMin(value = "0", message = "{payrollRate.rate.range}")
    @DecimalMax(value = "100", message = "{payrollRate.rate.range}")
    @Digits(integer = 3, fraction = 2, message = "{payrollRate.rate.range}")
    private BigDecimal occupationalHazardsRate;

    @NotNull(message = "{payrollRate.rate.required}")
    @DecimalMin(value = "0", message = "{payrollRate.rate.range}")
    @DecimalMax(value = "100", message = "{payrollRate.rate.range}")
    @Digits(integer = 3, fraction = 2, message = "{payrollRate.rate.range}")
    private BigDecimal maternityEmployeeRate;

    @NotNull(message = "{payrollRate.rate.required}")
    @DecimalMin(value = "0", message = "{payrollRate.rate.range}")
    @DecimalMax(value = "100", message = "{payrollRate.rate.range}")
    @Digits(integer = 3, fraction = 2, message = "{payrollRate.rate.range}")
    private BigDecimal maternityEmployerRate;

    @NotNull(message = "{payrollRate.rate.required}")
    @DecimalMin(value = "0", message = "{payrollRate.rate.range}")
    @DecimalMax(value = "100", message = "{payrollRate.rate.range}")
    @Digits(integer = 3, fraction = 2, message = "{payrollRate.rate.range}")
    private BigDecimal cbhiRate;

    private boolean transportInRssbBase;

    @Size(max = 255, message = "{payrollRate.note.size}")
    private String note;

    /** A form filled from a set: to edit it, or as the start of the next one. */
    public static PayrollRateDto of(PayrollRateSet s) {
        PayrollRateDto d = new PayrollRateDto();
        d.setId(s.getId());
        d.setEffectiveFrom(s.getEffectiveFrom());
        d.setPayeBand1(plain(s.getPayeBand1()));
        d.setPayeBand2(plain(s.getPayeBand2()));
        d.setPayeBand3(plain(s.getPayeBand3()));
        d.setPayeRate1(plain(s.getPayeRate1()));
        d.setPayeRate2(plain(s.getPayeRate2()));
        d.setPayeRate3(plain(s.getPayeRate3()));
        d.setPayeRate4(plain(s.getPayeRate4()));
        d.setPayeSecondaryRate(plain(s.getPayeSecondaryRate()));
        d.setPensionEmployeeRate(plain(s.getPensionEmployeeRate()));
        d.setPensionEmployerRate(plain(s.getPensionEmployerRate()));
        d.setOccupationalHazardsRate(plain(s.getOccupationalHazardsRate()));
        d.setMaternityEmployeeRate(plain(s.getMaternityEmployeeRate()));
        d.setMaternityEmployerRate(plain(s.getMaternityEmployerRate()));
        d.setCbhiRate(plain(s.getCbhiRate()));
        d.setTransportInRssbBase(s.isTransportInRssbBase());
        d.setNote(s.getNote());
        return d;
    }

    /** 300000.00 as 300000, 0.30 as 0.3: what a number field shows (never 3E+5). */
    static BigDecimal plain(BigDecimal value) {
        return value == null ? null : new BigDecimal(value.stripTrailingZeros().toPlainString());
    }
}
