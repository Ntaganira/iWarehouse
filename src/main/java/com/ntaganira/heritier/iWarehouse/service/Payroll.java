package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.enums.EmploymentType;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : Payroll.java
 * - Date      : 2026. 10. 11.
 * - User      : Hntaganira
 * - Desc      : A month's pay for one employee, from the rates in force (payroll_rate_sets). Gross = base pay, allowances,
 *               overtime and bonus. PAYE (Law 027/2022) on the gross: by the bands for a first employer, the secondary
 *               rate on all of it otherwise, rounded up to whole RWF as RRA's calculator does. RSSB pension, occupational
 *               hazards and maternity on the gross (without the transport allowance when the rates say so), each share
 *               rounded to whole RWF. CBHI on the net pay before it. Net = gross less PAYE, the employee's shares, CBHI
 *               and the other deductions. Pure.
 * </pre>
 */
public final class Payroll {

    static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

    private Payroll() {
    }

    /** The rates in force: bands and rates as percentages (10 = 10%). */
    public record Rates(BigDecimal band1, BigDecimal band2, BigDecimal band3, BigDecimal rate1, BigDecimal rate2, BigDecimal rate3,
                        BigDecimal rate4, BigDecimal secondaryRate, BigDecimal pensionEmployee, BigDecimal pensionEmployer,
                        BigDecimal occupationalHazards, BigDecimal maternityEmployee, BigDecimal maternityEmployer, BigDecimal cbhi,
                        boolean transportInRssbBase) {
    }

    /** What the employee earned in the month. Null counts as zero. */
    public record Earnings(BigDecimal basePay, BigDecimal transport, BigDecimal housing, BigDecimal other, BigDecimal overtime,
                           BigDecimal bonus) {

        public BigDecimal gross() {
            return nz(basePay).add(nz(transport)).add(nz(housing)).add(nz(other)).add(nz(overtime)).add(nz(bonus));
        }
    }

    /** What else is kept from the pay: salary advances, a driver's shortage, anything else. Null counts as zero. */
    public record Deductions(BigDecimal advance, BigDecimal shortage, BigDecimal other) {

        public static final Deductions NONE = new Deductions(null, null, null);

        public BigDecimal total() {
            return nz(advance).add(nz(shortage)).add(nz(other));
        }
    }

    /** The month's pay, every amount in RWF with 2 decimals (whole RWF for the statutory ones). */
    public record Result(BigDecimal gross, BigDecimal rssbBase, BigDecimal paye, BigDecimal pensionEmployee, BigDecimal pensionEmployer,
                         BigDecimal occupationalHazards, BigDecimal maternityEmployee, BigDecimal maternityEmployer, BigDecimal cbhi,
                         BigDecimal otherDeductions, BigDecimal net, BigDecimal employerCost) {

        /** The employee's RSSB shares (pension and maternity). */
        public BigDecimal getEmployeeContributions() {
            return pensionEmployee.add(maternityEmployee);
        }

        /** The employer's RSSB shares (pension, occupational hazards, maternity). */
        public BigDecimal getEmployerContributions() {
            return pensionEmployer.add(occupationalHazards).add(maternityEmployer);
        }

        /** The deductions are more than the pay. */
        public boolean isNegative() {
            return net.signum() < 0;
        }
    }

    public static Result calculate(Earnings earnings, Deductions deductions, EmploymentType employment, Rates rates) {
        BigDecimal gross = money(earnings.gross());
        BigDecimal paye = paye(gross, employment, rates);
        BigDecimal base = rates.transportInRssbBase() ? gross : money(gross.subtract(nz(earnings.transport())).max(BigDecimal.ZERO));
        BigDecimal pensionEmployee = share(base, rates.pensionEmployee());
        BigDecimal pensionEmployer = share(base, rates.pensionEmployer());
        BigDecimal hazards = share(base, rates.occupationalHazards());
        BigDecimal maternityEmployee = share(base, rates.maternityEmployee());
        BigDecimal maternityEmployer = share(base, rates.maternityEmployer());
        BigDecimal beforeCbhi = gross.subtract(paye).subtract(pensionEmployee).subtract(maternityEmployee);
        BigDecimal cbhi = share(beforeCbhi.max(BigDecimal.ZERO), rates.cbhi());
        BigDecimal other = money((deductions == null ? Deductions.NONE : deductions).total());
        BigDecimal net = beforeCbhi.subtract(cbhi).subtract(other);
        BigDecimal employerCost = gross.add(pensionEmployer).add(hazards).add(maternityEmployer);
        return new Result(gross, base, paye, pensionEmployee, pensionEmployer, hazards, maternityEmployee, maternityEmployer, cbhi, other,
                net, employerCost);
    }

    /** PAYE on a month's taxable pay: the bands for a first employer, the secondary rate on all of it otherwise; rounded up. */
    public static BigDecimal paye(BigDecimal taxable, EmploymentType employment, Rates rates) {
        if (taxable == null || taxable.signum() <= 0) {
            return ZERO;
        }
        BigDecimal tax;
        if (employment == EmploymentType.SECONDARY) {
            tax = taxable.multiply(rates.secondaryRate());
        } else {
            tax = slice(taxable, BigDecimal.ZERO, rates.band1()).multiply(rates.rate1())
                    .add(slice(taxable, rates.band1(), rates.band2()).multiply(rates.rate2()))
                    .add(slice(taxable, rates.band2(), rates.band3()).multiply(rates.rate3()))
                    .add(slice(taxable, rates.band3(), null).multiply(rates.rate4()));
        }
        return tax.divide(HUNDRED, 6, RoundingMode.HALF_UP).setScale(0, RoundingMode.CEILING).setScale(2);
    }

    /** The part of the amount between from and to (to null: no limit). */
    static BigDecimal slice(BigDecimal amount, BigDecimal from, BigDecimal to) {
        BigDecimal top = to == null ? amount : amount.min(to);
        return top.subtract(from).max(BigDecimal.ZERO);
    }

    /** A contribution: the rate of the base, rounded to whole RWF. */
    static BigDecimal share(BigDecimal base, BigDecimal rate) {
        if (base == null || rate == null || base.signum() <= 0 || rate.signum() <= 0) {
            return ZERO;
        }
        return base.multiply(rate).divide(HUNDRED, 0, RoundingMode.HALF_UP).setScale(2);
    }

    private static BigDecimal money(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP);
    }

    static BigDecimal nz(BigDecimal amount) {
        return amount == null ? BigDecimal.ZERO : amount;
    }
}
