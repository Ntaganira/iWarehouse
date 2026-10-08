package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.entity.CuttingJob;
import com.ntaganira.heritier.iWarehouse.entity.CuttingJobOutput;
import com.ntaganira.heritier.iWarehouse.enums.BreakageReason;
import com.ntaganira.heritier.iWarehouse.enums.CuttingOutputKind;

import java.math.BigDecimal;
import java.util.*;
import java.util.function.Function;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : CuttingYield.java
 * - Date      : 2026. 10. 08.
 * - User      : Hntaganira
 * - Desc      : Yield report sums, without the database (PRD-09, SRS 4.11 production reports): the m²
 *               consumed, stocked as pieces and off-cuts, lost as cullet and broken, and the yield %, for
 *               all the cuts of a period and per operator or product; breakage per reason (PRD-08).
 * </pre>
 */
public final class CuttingYield {

    private CuttingYield() {
    }

    /** Sums of some cuts. Yield = pieces and off-cuts / consumed. */
    public record Row(String key, String label, int jobs, BigDecimal consumed, BigDecimal pieces, BigDecimal offcuts,
                      BigDecimal cullet, BigDecimal culletKg, BigDecimal broken, BigDecimal spoilageCost) {

        static Row empty(String key, String label) {
            return new Row(key, label, 0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        }

        Row plus(CuttingJob job) {
            return new Row(key, label, jobs + 1, consumed.add(job.getSourceAreaM2()), pieces.add(job.getPiecesAreaM2()),
                    offcuts.add(job.getOffcutAreaM2()), cullet.add(job.getCulletAreaM2()), culletKg.add(job.getCulletKg()),
                    broken.add(job.getBrokenAreaM2()), spoilageCost.add(job.getSpoilageCost()));
        }

        /** m² that went to stock: pieces and off-cuts. */
        public BigDecimal getStocked() {
            return pieces.add(offcuts);
        }

        public BigDecimal getYieldPercent() {
            return Cutting.yieldPercent(getStocked(), consumed);
        }

        /** Cullet and breakage as a percentage of the m² consumed. */
        public BigDecimal getLossPercent() {
            return Cutting.yieldPercent(cullet.add(broken), consumed);
        }
    }

    /** Glass broken while cutting, for one reason. */
    public record Breakage(BreakageReason reason, int pieces, BigDecimal areaM2, BigDecimal cost) {
    }

    /** Everything cut. */
    public static Row total(Collection<CuttingJob> jobs) {
        Row row = Row.empty("", "");
        for (CuttingJob job : jobs) {
            row = row.plus(job);
        }
        return row;
    }

    /** One row per key (operator, product...), ordered by the m² consumed, largest first. */
    public static List<Row> by(Collection<CuttingJob> jobs, Function<CuttingJob, String> key,
                               Function<CuttingJob, String> label) {
        Map<String, Row> rows = new LinkedHashMap<>();
        for (CuttingJob job : jobs) {
            String k = key.apply(job);
            rows.put(k, rows.getOrDefault(k, Row.empty(k, label.apply(job))).plus(job));
        }
        List<Row> list = new ArrayList<>(rows.values());
        list.sort(Comparator.comparing(Row::consumed).reversed().thenComparing(Row::label));
        return list;
    }

    /** Breakage per reason, in the order of the reasons. */
    public static List<Breakage> breakage(Collection<CuttingJobOutput> outputs) {
        Map<BreakageReason, Breakage> rows = new EnumMap<>(BreakageReason.class);
        for (CuttingJobOutput o : outputs) {
            if (o.getKind() != CuttingOutputKind.BROKEN) {
                continue;
            }
            Breakage b = rows.getOrDefault(o.getReason(), new Breakage(o.getReason(), 0, BigDecimal.ZERO, BigDecimal.ZERO));
            rows.put(o.getReason(), new Breakage(o.getReason(), b.pieces() + o.getQuantity(), b.areaM2().add(o.getAreaM2()),
                    b.cost().add(o.getCost())));
        }
        return new ArrayList<>(rows.values());
    }
}
