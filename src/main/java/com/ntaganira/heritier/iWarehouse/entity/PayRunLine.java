package com.ntaganira.heritier.iWarehouse.entity;

import com.ntaganira.heritier.iWarehouse.audit.AuditMask;
import com.ntaganira.heritier.iWarehouse.audit.AuditedEntity;
import com.ntaganira.heritier.iWarehouse.enums.EmploymentType;
import com.ntaganira.heritier.iWarehouse.enums.SalaryPayMethod;
import com.ntaganira.heritier.iWarehouse.service.Payroll;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.entity
 * - File      : PayRunLine.java
 * - Date      : 2026. 10. 11.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : One employee's pay in a run: what they earned (from their salary, changed for the month while the run is a
 *               draft), what was kept (Payroll) and their net pay. Their name, IDs and account are copied when the line is
 *               made, so payslips and declarations never change. A posted run's lines never change (trg_pay_run_lines_posted).
 * </pre>
 */
@Entity
@Table(name = "pay_run_lines")
@AuditedEntity(ref = "employeeCode")
@Getter
@Setter
@NoArgsConstructor
public class PayRunLine extends BaseEntity {

    @Column(name = "pay_run_id", nullable = false, updatable = false)
    private UUID payRunId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "employee_id", nullable = false, updatable = false)
    private Employee employee;

    /** An employee who drives: their shortage (Driver Shortage Receivable) is recovered here. */
    @Column(name = "driver_id")
    private UUID driverId;

    /** A staff advance not retired in time, recovered by the advance deduction (CMP 08). */
    @Column(name = "advance_id")
    private UUID advanceId;

    /** The employee's cost centre, copied: the salary posts to it (F-15 A). */
    @Column(name = "cost_centre_id")
    private UUID costCentreId;

    @Column(name = "employee_code", nullable = false, length = 20, updatable = false)
    private String employeeCode;

    @Column(name = "employee_name", nullable = false, length = 100)
    private String employeeName;

    @AuditMask
    @Column(name = "national_id", nullable = false, length = 16)
    private String nationalId;

    @Column(name = "rssb_number", length = 20)
    private String rssbNumber;

    @Column(name = "job_title", length = 100)
    private String jobTitle;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private EmploymentType employment;

    @Enumerated(EnumType.STRING)
    @Column(name = "pay_method", nullable = false, length = 15)
    private SalaryPayMethod payMethod;

    @AuditMask
    @Column(name = "account_number", length = 40)
    private String accountNumber;

    @Column(name = "base_pay", nullable = false, precision = 18, scale = 2)
    private BigDecimal basePay = BigDecimal.ZERO;

    @Column(name = "transport_allowance", nullable = false, precision = 18, scale = 2)
    private BigDecimal transportAllowance = BigDecimal.ZERO;

    @Column(name = "housing_allowance", nullable = false, precision = 18, scale = 2)
    private BigDecimal housingAllowance = BigDecimal.ZERO;

    @Column(name = "other_allowance", nullable = false, precision = 18, scale = 2)
    private BigDecimal otherAllowance = BigDecimal.ZERO;

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal overtime = BigDecimal.ZERO;

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal bonus = BigDecimal.ZERO;

    @Column(name = "advance_deduction", nullable = false, precision = 18, scale = 2)
    private BigDecimal advanceDeduction = BigDecimal.ZERO;

    @Column(name = "shortage_deduction", nullable = false, precision = 18, scale = 2)
    private BigDecimal shortageDeduction = BigDecimal.ZERO;

    @Column(name = "other_deduction", nullable = false, precision = 18, scale = 2)
    private BigDecimal otherDeduction = BigDecimal.ZERO;

    @Column(name = "deduction_note", length = 255)
    private String deductionNote;

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal gross = BigDecimal.ZERO;

    @Column(name = "rssb_base", nullable = false, precision = 18, scale = 2)
    private BigDecimal rssbBase = BigDecimal.ZERO;

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal paye = BigDecimal.ZERO;

    @Column(name = "pension_employee", nullable = false, precision = 18, scale = 2)
    private BigDecimal pensionEmployee = BigDecimal.ZERO;

    @Column(name = "pension_employer", nullable = false, precision = 18, scale = 2)
    private BigDecimal pensionEmployer = BigDecimal.ZERO;

    @Column(name = "occupational_hazards", nullable = false, precision = 18, scale = 2)
    private BigDecimal occupationalHazards = BigDecimal.ZERO;

    @Column(name = "maternity_employee", nullable = false, precision = 18, scale = 2)
    private BigDecimal maternityEmployee = BigDecimal.ZERO;

    @Column(name = "maternity_employer", nullable = false, precision = 18, scale = 2)
    private BigDecimal maternityEmployer = BigDecimal.ZERO;

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal cbhi = BigDecimal.ZERO;

    @Column(name = "net_pay", nullable = false, precision = 18, scale = 2)
    private BigDecimal netPay = BigDecimal.ZERO;

    @Column(name = "employer_cost", nullable = false, precision = 18, scale = 2)
    private BigDecimal employerCost = BigDecimal.ZERO;

    public Payroll.Earnings earnings() {
        return new Payroll.Earnings(basePay, transportAllowance, housingAllowance, otherAllowance, overtime, bonus);
    }

    public Payroll.Deductions deductions() {
        return new Payroll.Deductions(advanceDeduction, shortageDeduction, otherDeduction);
    }

    /** Takes the calculated amounts. */
    public void apply(Payroll.Result r) {
        gross = r.gross();
        rssbBase = r.rssbBase();
        paye = r.paye();
        pensionEmployee = r.pensionEmployee();
        pensionEmployer = r.pensionEmployer();
        occupationalHazards = r.occupationalHazards();
        maternityEmployee = r.maternityEmployee();
        maternityEmployer = r.maternityEmployer();
        cbhi = r.cbhi();
        netPay = r.net();
        employerCost = r.employerCost();
    }

    /** The employee's RSSB shares (pension and maternity). */
    public BigDecimal getEmployeeContributions() {
        return pensionEmployee.add(maternityEmployee);
    }

    /** The employer's RSSB shares (pension, occupational hazards, maternity). */
    public BigDecimal getEmployerContributions() {
        return pensionEmployer.add(occupationalHazards).add(maternityEmployer);
    }

    /** Advances, shortage and other deductions. */
    public BigDecimal getOtherDeductions() {
        return advanceDeduction.add(shortageDeduction).add(otherDeduction);
    }

    /** Everything kept from the gross. */
    public BigDecimal getTotalDeductions() {
        return paye.add(getEmployeeContributions()).add(cbhi).add(getOtherDeductions());
    }

    /** What is owed to RSSB for this employee: both shares of the contributions and CBHI. */
    public BigDecimal getRssbTotal() {
        return getEmployeeContributions().add(getEmployerContributions()).add(cbhi);
    }
}
