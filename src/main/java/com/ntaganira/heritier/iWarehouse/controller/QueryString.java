package com.ntaganira.heritier.iWarehouse.controller;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.controller
 * - File      : QueryString.java
 * - Date      : 2026. 10. 07.
 * - User      : Hntaganira
 * - Desc      : Builds the filter query that list pages hand to layout/pagination (no leading "?")
 * </pre>
 */
final class QueryString {

    private QueryString() {
    }

    /** Pairs of name, value; null and blank values are left out. */
    static String of(Object... pairs) {
        StringBuilder q = new StringBuilder();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            Object value = pairs[i + 1];
            if (value == null || value.toString().isBlank()) {
                continue;
            }
            if (q.length() > 0) {
                q.append('&');
            }
            q.append(pairs[i]).append('=').append(URLEncoder.encode(value.toString().trim(), StandardCharsets.UTF_8));
        }
        return q.toString();
    }
}
