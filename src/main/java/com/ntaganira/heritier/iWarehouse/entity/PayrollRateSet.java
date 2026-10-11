package com.ntaganira.heritier.iWarehouse.entity;

import com.ntaganira.heritier.iWarehouse.audit.AuditedEntity;
import com.ntaganira.heritier.iWarehouse.service.Payroll;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.entity
 * - File      : PayrollRateSet.java
 * - Date      : 2026. 10. 11.
 * - User      : Hntaganira
 * - Desc      : The PAYE bands and rates and the RSSB and CBHI rates in force from a date (payroll). A run takes the set in
 *               force on its month's last day and keeps it; a set a posted run used is never changed: a change in the law
 *               is a new set from its date.
 * </pre>
 */
@Entity
@Table(name = "payroll_rate_sets")
@AuditedEntity(ref = "effectiveFrom")
@Getter
@Setter
@NoArgsConstructor
public class PayrollRateSet extends BaseEntity {

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @Column(name = "paye_band1", nullable = false, precision = 18, scale = 2)
    private BigDecimal payeBand1;

    @Column(name = "paye_band2", nullable = false, precision = 18, scale = 2)
    private BigDecimal payeBand2;

    @Column(name = "paye_band3", nullable = false, precision = 18, scale = 2)
    private BigDecimal payeBand3;

    @Column(name = "paye_rate1", nullable = false, precision = 5, scale = 2)
    private BigDecimal payeRate1;

    @Column(name = "paye_rate2", nullable = false, precision = 5, scale = 2)
    private BigDecimal payeRate2;

    @Column(name = "paye_rate3", nullable = false, precision = 5, scale = 2)
    private BigDecimal payeRate3;

    @Column(name = "paye_rate4", nullable = false, precision = 5, scale = 2)
    private BigDecimal payeRate4;

    @Column(name = "paye_secondary_rate", nullable = false, precision = 5, scale = 2)
    private BigDecimal payeSecondaryRate;

    @Column(name = "pension_employee_rate", nullable = false, precision = 5, scale = 2)
    private BigDecimal pensionEmployeeRate;

    @Column(name = "pension_employer_rate", nullable = false, precision = 5, scale = 2)
    private BigDecimal pensionEmployerRate;

    @Column(name = "occupational_hazards_rate", nullable = false, precision = 5, scale = 2)
    private BigDecimal occupationalHazardsRate;

    @Column(name = "maternity_employee_rate", nullable = false, precision = 5, scale = 2)
    private BigDecimal maternityEmployeeRate;

    @Column(name = "maternity_employer_rate", nullable = false, precision = 5, scale = 2)
    private BigDecimal maternityEmployerRate;

    @Column(name = "cbhi_rate", nullable = false, precision = 5, scale = 2)
    private BigDecimal cbhiRate;

    @Column(name = "transport_in_rssb_base", nullable = false)
    private boolean transportInRssbBase;

    @Column(length = 255)
    private String note;

    /** The rates for the calculation. */
    public Payroll.Rates rates() {
        return new Payroll.Rates(payeBand1, payeBand2, payeBand3, payeRate1, payeRate2, payeRate3, payeRate4, payeSecondaryRate,
                pensionEmployeeRate, pensionEmployerRate, occupationalHazardsRate, maternityEmployeeRate, maternityEmployerRate, cbhiRate,
                transportInRssbBase);
    }
}
