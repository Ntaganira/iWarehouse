package com.ntaganira.heritier.iWarehouse.ebm;

import java.time.Duration;
import java.util.Set;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.ebm
 * - File      : VsdcResults.java
 * - Date      : 2026. 10. 10.
 * - User      : Hntaganira
 * - Desc      : What a VSDC result code means for a queued receipt (TAX-03, spec 4.14), and how long to wait before
 *               the next attempt. 000 is signed. Codes about the device, the connection to RRA or RRA's server
 *               (89x, 900-906, 922, 990-996, 999) are retried: they clear without changing the receipt. 924 and 994
 *               say RRA already holds this invoice number (an earlier attempt was signed but its answer was lost):
 *               someone must look. Anything else refuses the receipt's data (910 parameter error, 913 unknown code,
 *               881-884 purchase code or customer TIN): it waits for a person. Pure, unit-tested.
 * </pre>
 */
public final class VsdcResults {

    /** What becomes of the receipt. */
    public enum Outcome {
        SIGNED, RETRY, DUPLICATE, REJECTED
    }

    public static final String OK = "000";

    private static final Set<String> DUPLICATES = Set.of("924", "994");
    private static final Set<String> RETRIED = Set.of("891", "892", "893", "894", "895", "896", "899",
            "900", "901", "902", "903", "904", "905", "906", "922", "990", "991", "992", "993", "995", "996", "999");

    /** Waits after the first failed attempt; doubled after each, at most MAX_DELAY. */
    public static final Duration FIRST_DELAY = Duration.ofSeconds(30);
    public static final Duration MAX_DELAY = Duration.ofMinutes(30);

    private VsdcResults() {
    }

    public static Outcome of(String code) {
        if (OK.equals(code)) {
            return Outcome.SIGNED;
        }
        if (code != null && DUPLICATES.contains(code)) {
            return Outcome.DUPLICATE;
        }
        if (code != null && RETRIED.contains(code)) {
            return Outcome.RETRY;
        }
        return Outcome.REJECTED;
    }

    /** How long to wait after {@code attempts} failed attempts (1 = the first): 30 s, 1 min, 2 min, ... 30 min. */
    public static Duration retryDelay(int attempts) {
        int doublings = Math.max(0, Math.min(attempts - 1, 10));
        Duration delay = FIRST_DELAY.multipliedBy(1L << doublings);
        return delay.compareTo(MAX_DELAY) > 0 ? MAX_DELAY : delay;
    }
}
