package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.entity.CuttingJob;
import com.ntaganira.heritier.iWarehouse.entity.CuttingJobOutput;
import com.ntaganira.heritier.iWarehouse.entity.Product;
import com.ntaganira.heritier.iWarehouse.enums.BreakageReason;
import com.ntaganira.heritier.iWarehouse.enums.CuttingOutputKind;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Yield report sums (PRD-09): totals, per operator and product, breakage per reason (PRD-08). */
class CuttingYieldTest {

    private final Product clear6 = product("CLR-6");
    private final Product clear8 = product("CLR-8");

    @Test
    void yieldIsStockedAreaOverConsumedArea() {
        List<CuttingJob> jobs = List.of(
                job("op1", clear6, "7.2225", "5.0000", "1.8000", "0.4225", "6.34", "0", "14566.97"),
                job("op1", clear6, "4.0000", "3.0000", "0", "0.5000", "7.50", "0.5000", "1000.00"),
                job("op2", clear8, "4.4652", "4.0000", "0", "0.4652", "9.30", "0", "800.00"));

        CuttingYield.Row total = CuttingYield.total(jobs);

        assertThat(total.jobs()).isEqualTo(3);
        assertThat(total.consumed()).isEqualByComparingTo("15.6877");
        assertThat(total.getStocked()).isEqualByComparingTo("13.8000");
        assertThat(total.cullet()).isEqualByComparingTo("1.3877");
        assertThat(total.broken()).isEqualByComparingTo("0.5000");
        assertThat(total.culletKg()).isEqualByComparingTo("23.14");
        assertThat(total.getYieldPercent()).isEqualByComparingTo("87.97");
        assertThat(total.getLossPercent()).isEqualByComparingTo("12.03");
        assertThat(total.spoilageCost()).isEqualByComparingTo("16366.97");
    }

    @Test
    void rowsPerOperatorAreOrderedByAreaCut() {
        List<CuttingJob> jobs = List.of(
                job("op2", clear8, "4.4652", "4.0000", "0", "0.4652", "9.30", "0", "800.00"),
                job("op1", clear6, "7.2225", "5.0000", "1.8000", "0.4225", "6.34", "0", "14566.97"),
                job("op1", clear6, "4.0000", "3.0000", "0", "0.5000", "7.50", "0.5000", "1000.00"));

        List<CuttingYield.Row> rows = CuttingYield.by(jobs, CuttingJob::getOperatorName, CuttingJob::getOperatorName);

        assertThat(rows).extracting(CuttingYield.Row::label).containsExactly("op1", "op2");
        assertThat(rows.get(0).jobs()).isEqualTo(2);
        assertThat(rows.get(0).consumed()).isEqualByComparingTo("11.2225");
        assertThat(rows.get(0).getYieldPercent()).isEqualByComparingTo("87.32");
        assertThat(rows.get(1).getYieldPercent()).isEqualByComparingTo("89.58");
    }

    @Test
    void breakageIsSummedPerReason() {
        List<CuttingJobOutput> outputs = List.of(
                output(CuttingOutputKind.BROKEN, BreakageReason.HANDLING, 1, "1.5000", "51717.05"),
                output(CuttingOutputKind.BROKEN, BreakageReason.GLASS_DEFECT, 2, "0.4000", "13791.21"),
                output(CuttingOutputKind.BROKEN, BreakageReason.HANDLING, 1, "0.2000", "6895.61"),
                output(CuttingOutputKind.CULLET, null, 1, "0.4225", "14566.97"));

        List<CuttingYield.Breakage> rows = CuttingYield.breakage(outputs);

        assertThat(rows).extracting(CuttingYield.Breakage::reason)
                .containsExactly(BreakageReason.HANDLING, BreakageReason.GLASS_DEFECT);
        assertThat(rows.get(0).pieces()).isEqualTo(2);
        assertThat(rows.get(0).areaM2()).isEqualByComparingTo("1.7000");
        assertThat(rows.get(0).cost()).isEqualByComparingTo("58612.66");
        assertThat(rows.get(1).pieces()).isEqualTo(2);
    }

    private static CuttingJob job(String operator, Product product, String source, String pieces, String offcuts,
                                  String cullet, String culletKg, String broken, String spoilage) {
        CuttingJob job = new CuttingJob();
        job.setId(UUID.randomUUID());
        job.setOperatorName(operator);
        job.setProduct(product);
        job.setSourceAreaM2(new BigDecimal(source));
        job.setPiecesAreaM2(new BigDecimal(pieces));
        job.setOffcutAreaM2(new BigDecimal(offcuts));
        job.setCulletAreaM2(new BigDecimal(cullet));
        job.setCulletKg(new BigDecimal(culletKg));
        job.setBrokenAreaM2(new BigDecimal(broken));
        job.setCulletCost(new BigDecimal(spoilage));
        job.setBrokenCost(BigDecimal.ZERO);
        return job;
    }

    private static CuttingJobOutput output(CuttingOutputKind kind, BreakageReason reason, int qty, String area, String cost) {
        CuttingJobOutput o = new CuttingJobOutput();
        o.setKind(kind);
        o.setReason(reason);
        o.setQuantity(qty);
        o.setAreaM2(new BigDecimal(area));
        o.setCost(new BigDecimal(cost));
        return o;
    }

    private static Product product(String code) {
        Product p = new Product();
        p.setId(UUID.randomUUID());
        p.setCode(code);
        return p;
    }
}
