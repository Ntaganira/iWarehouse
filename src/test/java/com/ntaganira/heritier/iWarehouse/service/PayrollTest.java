package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.enums.EmploymentType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A month's pay on worked examples at the rates seeded by V40: PAYE 0% to 60,000, 10% to 100,000, 20% to 200,000, 30% above
 * (30% from a second employer); RSSB pension 6% + 6%, occupational hazards 2%, maternity 0.3% + 0.3%; CBHI 0.5% of the net.
 */
class PayrollTest {

    private static final Payroll.Rates RATES = rates(true);

    static Payroll.Rates rates(boolean transportInRssbBase) {
        return new Payroll.Rates(bd("60000"), bd("100000"), bd("200000"), bd("0"), bd("10"), bd("20"), bd("30"), bd("30"),
                bd("6"), bd("6"), bd("2"), bd("0.3"), bd("0.3"), bd("0.5"), transportInRssbBase);
    }

    @Test
    void aSalaryOf300000WithTransportInTheRssbBase() {
        Payroll.Result r = Payroll.calculate(earnings("250000", "50000"), Payroll.Deductions.NONE, EmploymentType.PRIMARY, RATES);

        assertThat(r.gross()).isEqualByComparingTo("300000");
        assertThat(r.paye()).isEqualByComparingTo("54000");               // 0 + 4,000 + 20,000 + 30,000
        assertThat(r.rssbBase()).isEqualByComparingTo("300000");
        assertThat(r.pensionEmployee()).isEqualByComparingTo("18000");
        assertThat(r.pensionEmployer()).isEqualByComparingTo("18000");
        assertThat(r.occupationalHazards()).isEqualByComparingTo("6000");
        assertThat(r.maternityEmployee()).isEqualByComparingTo("900");
        assertThat(r.maternityEmployer()).isEqualByComparingTo("900");
        assertThat(r.cbhi()).isEqualByComparingTo("1136");                // 0.5% of 227,100 = 1,135.50
        assertThat(r.net()).isEqualByComparingTo("225964");
        assertThat(r.employerCost()).isEqualByComparingTo("324900");
        assertThat(r.getEmployeeContributions()).isEqualByComparingTo("18900");
        assertThat(r.getEmployerContributions()).isEqualByComparingTo("24900");
    }

    @Test
    void theTransportAllowanceLeftOutOfTheRssbBaseWhenTheRatesSaySo() {
        Payroll.Result r = Payroll.calculate(earnings("250000", "50000"), Payroll.Deductions.NONE, EmploymentType.PRIMARY, rates(false));

        assertThat(r.paye()).isEqualByComparingTo("54000");               // PAYE still on everything
        assertThat(r.rssbBase()).isEqualByComparingTo("250000");
        assertThat(r.pensionEmployee()).isEqualByComparingTo("15000");
        assertThat(r.occupationalHazards()).isEqualByComparingTo("5000");
        assertThat(r.maternityEmployee()).isEqualByComparingTo("750");
        assertThat(r.cbhi()).isEqualByComparingTo("1151");                // 0.5% of 230,250 = 1,151.25
        assertThat(r.net()).isEqualByComparingTo("229099");
    }

    @Test
    void payUpToTheFirstBandIsNotTaxed() {
        Payroll.Result r = Payroll.calculate(earnings("60000", null), Payroll.Deductions.NONE, EmploymentType.PRIMARY, RATES);

        assertThat(r.paye()).isEqualByComparingTo("0");
        assertThat(r.pensionEmployee()).isEqualByComparingTo("3600");
        assertThat(r.maternityEmployee()).isEqualByComparingTo("180");
        assertThat(r.cbhi()).isEqualByComparingTo("281");                 // 0.5% of 56,220 = 281.10
        assertThat(r.net()).isEqualByComparingTo("55939");
    }

    @Test
    void payeAtTheEdgesOfTheBandsIsRoundedUp() {
        assertThat(paye("60001")).isEqualByComparingTo("1");              // 0.10 rounded up, as RRA's calculator does
        assertThat(paye("100000")).isEqualByComparingTo("4000");
        assertThat(paye("100001")).isEqualByComparingTo("4001");          // 4,000.20
        assertThat(paye("200000")).isEqualByComparingTo("24000");
        assertThat(paye("200001")).isEqualByComparingTo("24001");         // 24,000.30
        assertThat(paye("1000000")).isEqualByComparingTo("264000");       // 24,000 + 30% of 800,000
        assertThat(paye("0")).isEqualByComparingTo("0");
    }

    @Test
    void aSecondEmployerWithholdsTheSecondaryRateOnEverything() {
        assertThat(Payroll.paye(bd("150000"), EmploymentType.SECONDARY, RATES)).isEqualByComparingTo("45000");
        assertThat(Payroll.paye(bd("50000"), EmploymentType.SECONDARY, RATES)).isEqualByComparingTo("15000");   // no free band
    }

    @Test
    void otherDeductionsComeOffTheNetPayOnly() {
        Payroll.Result r = Payroll.calculate(earnings("250000", "50000"), new Payroll.Deductions(bd("20000"), bd("1000"), bd("5000")),
                EmploymentType.PRIMARY, RATES);

        assertThat(r.cbhi()).isEqualByComparingTo("1136");                // CBHI before the other deductions
        assertThat(r.otherDeductions()).isEqualByComparingTo("26000");
        assertThat(r.net()).isEqualByComparingTo("199964");
        assertThat(r.isNegative()).isFalse();
    }

    @Test
    void deductionsAboveThePayAreToldApart() {
        Payroll.Result r = Payroll.calculate(earnings("50000", null), new Payroll.Deductions(bd("60000"), null, null),
                EmploymentType.PRIMARY, RATES);

        assertThat(r.isNegative()).isTrue();
    }

    @Test
    void noPayNothingWithheld() {
        Payroll.Result r = Payroll.calculate(earnings("0", null), Payroll.Deductions.NONE, EmploymentType.PRIMARY, RATES);

        assertThat(r.gross()).isEqualByComparingTo("0");
        assertThat(r.net()).isEqualByComparingTo("0");
        assertThat(r.employerCost()).isEqualByComparingTo("0");
    }

    @Test
    void overtimeAndBonusArePartOfTheGross() {
        Payroll.Earnings e = new Payroll.Earnings(bd("200000"), bd("20000"), bd("30000"), bd("10000"), bd("15000"), bd("25000"));

        assertThat(e.gross()).isEqualByComparingTo("300000");
        assertThat(Payroll.calculate(e, Payroll.Deductions.NONE, EmploymentType.PRIMARY, RATES).paye()).isEqualByComparingTo("54000");
    }

    private static BigDecimal paye(String taxable) {
        return Payroll.paye(bd(taxable), EmploymentType.PRIMARY, RATES);
    }

    private static Payroll.Earnings earnings(String base, String transport) {
        return new Payroll.Earnings(bd(base), transport == null ? null : bd(transport), null, null, null, null);
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
