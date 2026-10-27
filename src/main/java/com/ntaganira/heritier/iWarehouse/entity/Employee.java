package com.ntaganira.heritier.iWarehouse.entity;

import com.ntaganira.heritier.iWarehouse.audit.AuditMask;
import com.ntaganira.heritier.iWarehouse.audit.AuditedEntity;
import com.ntaganira.heritier.iWarehouse.enums.EmploymentType;
import com.ntaganira.heritier.iWarehouse.enums.SalaryPayMethod;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.entity
 * - File      : Employee.java
 * - Date      : 2026. 10. 11.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : Someone the business pays (payroll): their monthly salary and allowances, how PAYE is withheld (first or
 *               second employer), how they are paid. Linked to the user they sign in with, they find their payslips on My
 *               profile; a driver's shortage is recovered from their pay. The national ID and account number are personal
 *               data (NFR-12, Law 058/2021): in full only with PERM_VIEW_EMPLOYEE_DATA, masked in the change log. Never
 *               deleted: an employee who left keeps their last day and leaves the next runs.
 * </pre>
 */
@Entity
@Table(name = "employees")
@AuditedEntity(ref = "code")
@Getter
@Setter
@NoArgsConstructor
public class Employee extends BaseEntity {

    @Column(nullable = false, unique = true, length = 20, updatable = false)
    private String code;

    @Column(name = "full_name", nullable = false, length = 100)
    private String fullName;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @AuditMask
    @Column(name = "national_id", nullable = false, length = 16)
    private String nationalId;

    @Column(name = "rssb_number", length = 20)
    private String rssbNumber;

    @Column(name = "job_title", length = 100)
    private String jobTitle;

    /** The department their pay is posted to (F-15 A). */
    @Column(name = "cost_centre_id")
    private UUID costCentreId;

    @Column(name = "hired_on", nullable = false)
    private LocalDate hiredOn;

    /** The last day worked: not in the runs of later months. */
    @Column(name = "left_on")
    private LocalDate leftOn;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private EmploymentType employment = EmploymentType.PRIMARY;

    @Column(name = "base_salary", nullable = false, precision = 18, scale = 2)
    private BigDecimal baseSalary;

    @Column(name = "transport_allowance", nullable = false, precision = 18, scale = 2)
    private BigDecimal transportAllowance = BigDecimal.ZERO;

    @Column(name = "housing_allowance", nullable = false, precision = 18, scale = 2)
    private BigDecimal housingAllowance = BigDecimal.ZERO;

    @Column(name = "other_allowance", nullable = false, precision = 18, scale = 2)
    private BigDecimal otherAllowance = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(name = "pay_method", nullable = false, length = 15)
    private SalaryPayMethod payMethod = SalaryPayMethod.BANK;

    @Column(name = "bank_name", length = 60)
    private String bankName;

    @AuditMask
    @Column(name = "account_number", length = 40)
    private String accountNumber;

    /** Paid in the month that starts on this day: hired by its end and not gone before it started. */
    public boolean worksIn(LocalDate period) {
        LocalDate end = period.plusMonths(1).minusDays(1);
        return !hiredOn.isAfter(end) && (leftOn == null || !leftOn.isBefore(period));
    }

    public boolean isActive() {
        return leftOn == null;
    }

    /** The salary and allowances of a month. */
    public BigDecimal getMonthlyPay() {
        return baseSalary.add(transportAllowance).add(housingAllowance).add(otherAllowance);
    }
}
