package com.ntaganira.heritier.iWarehouse.dto;

import com.ntaganira.heritier.iWarehouse.enums.EmploymentType;
import com.ntaganira.heritier.iWarehouse.enums.SalaryPayMethod;
import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.format.annotation.DateTimeFormat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Locale;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.dto
 * - File      : EmployeeDto.java
 * - Date      : 2026. 10. 11.
 * - User      : Hntaganira
 * - Desc      : The employee form (payroll): who they are, their monthly salary and allowances in whole RWF, how PAYE is
 *               withheld and how they are paid. Spaces are dropped from the national ID and account number.
 * </pre>
 */
@Getter
@Setter
@NoArgsConstructor
public class EmployeeDto {

    private UUID id;

    @NotBlank(message = "{employee.fullName.required}")
    @Size(max = 100, message = "{employee.fullName.size}")
    private String fullName;

    private Long userId;

    @NotBlank(message = "{employee.nationalId.required}")
    @Pattern(regexp = "^$|^[0-9]{16}$", message = "{employee.nationalId.pattern}")
    private String nationalId;

    @Pattern(regexp = "^$|^[A-Za-z0-9/-]{3,20}$", message = "{employee.rssb.pattern}")
    private String rssbNumber;

    @Size(max = 100, message = "{employee.jobTitle.size}")
    private String jobTitle;

    /** The department their pay is posted to (F-15 A). */
    private UUID costCentreId;

    @NotNull(message = "{employee.hiredOn.required}")
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate hiredOn;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate leftOn;

    @NotNull(message = "{employee.employment.required}")
    private EmploymentType employment = EmploymentType.PRIMARY;

    @NotNull(message = "{employee.salary.required}")
    @DecimalMin(value = "0", message = "{payroll.amount.range}")
    @Digits(integer = 12, fraction = 0, message = "{payroll.amount.range}")
    private BigDecimal baseSalary;

    @DecimalMin(value = "0", message = "{payroll.amount.range}")
    @Digits(integer = 12, fraction = 0, message = "{payroll.amount.range}")
    private BigDecimal transportAllowance;

    @DecimalMin(value = "0", message = "{payroll.amount.range}")
    @Digits(integer = 12, fraction = 0, message = "{payroll.amount.range}")
    private BigDecimal housingAllowance;

    @DecimalMin(value = "0", message = "{payroll.amount.range}")
    @Digits(integer = 12, fraction = 0, message = "{payroll.amount.range}")
    private BigDecimal otherAllowance;

    @NotNull(message = "{employee.payMethod.required}")
    private SalaryPayMethod payMethod = SalaryPayMethod.BANK;

    @Size(max = 60, message = "{employee.bankName.size}")
    private String bankName;

    @Pattern(regexp = "^$|^[A-Za-z0-9+/-]{5,40}$", message = "{employee.account.pattern}")
    private String accountNumber;

    public void setNationalId(String nationalId) {
        this.nationalId = nationalId == null ? null : nationalId.replaceAll("\\s+", "");
    }

    public void setRssbNumber(String rssbNumber) {
        this.rssbNumber = rssbNumber == null ? null : rssbNumber.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
    }

    public void setAccountNumber(String accountNumber) {
        this.accountNumber = accountNumber == null ? null : accountNumber.replaceAll("\\s+", "");
    }
}
