package com.ntaganira.heritier.iWarehouse.config;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.List;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.config
 * - File      : Paging.java
 * - Date      : 2026. 10. 08.
 * - User      : Hntaganira
 * - Desc      : One page size for every table, and paging of lists already in memory (a unit's movements,
 *               a price list's rows, a report's groups) so every table shows the same page numbers.
 *               A detail page with tabs pages the open tab with ?tab=x&page=n; the other tabs start at 0.
 * </pre>
 */
public final class Paging {

    /** Rows per page on every table. */
    public static final int SIZE = 20;

    private Paging() {
    }

    /** The page number asked for, never below 0. */
    public static int page(int requested) {
        return Math.max(requested, 0);
    }

    /** The page asked for when its tab is open, else the first page. */
    public static int pageOf(String tab, String openTab, int requested) {
        return tab.equals(openTab) ? page(requested) : 0;
    }

    /** One page of a list held in memory; a page past the end shows the last one. */
    public static <T> Page<T> of(List<T> all, int requested) {
        int total = all.size();
        int last = total == 0 ? 0 : (total - 1) / SIZE;
        int page = Math.min(page(requested), last);
        int from = page * SIZE;
        List<T> content = all.subList(Math.min(from, total), Math.min(from + SIZE, total));
        return new PageImpl<>(List.copyOf(content), PageRequest.of(page, SIZE), total);
    }
}
