package com.ntaganira.heritier.iWarehouse.dto;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.dto
 * - File      : Trend.java
 * - Date      : 2026. 10. 07.
 * - User      : Hntaganira
 * - Desc      : A dashboard count compared with the same window of an earlier period (KPI card trend)
 * </pre>
 */
public record Trend(long current, long previous) {

    /** Change as a whole percentage of the previous value, or null when the previous value is zero. */
    public Integer percent() {
        if (previous == 0) {
            return null;
        }
        return (int) Math.round((current - previous) * 100.0 / previous);
    }

    /** Direction as shown: follows the rounded percentage, so "0%" is never drawn with an arrow. */
    public int sign() {
        Integer pct = percent();
        return pct != null ? Integer.signum(pct) : Long.signum(current - previous);
    }

    public boolean isUp() {
        return sign() > 0;
    }

    public boolean isDown() {
        return sign() < 0;
    }

    public boolean isFlat() {
        return sign() == 0;
    }

    /** "+12%", "-5%" or "0%"; an absolute "+3" when there is no earlier value to divide by. */
    public String label() {
        Integer pct = percent();
        if (pct == null) {
            return current == 0 ? "0" : "+" + current;
        }
        return (pct > 0 ? "+" : "") + pct + "%";
    }
}
