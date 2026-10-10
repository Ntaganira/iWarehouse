package com.ntaganira.heritier.iWarehouse.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : PrintTable.java
 * - Date      : 2026. 10. 10.
 * - User      : Hntaganira
 * - Desc      : A report sheet ready to print (RPT-07): the same sheet as the Excel export, each cell as the text the
 *               PDF shows. Amounts show the most decimals of their column (money 2, m² 4: Excel.scales) with a comma
 *               between thousands, counts are whole, dates dd/MM/yyyy; a column holding numbers is aligned right, header
 *               included. Pure, unit-tested.
 * </pre>
 */
public final class PrintTable {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DecimalFormatSymbols SYMBOLS = DecimalFormatSymbols.getInstance(Locale.US);

    /** More columns than this print on a landscape page. */
    public static final int PORTRAIT_COLUMNS = 6;

    private PrintTable() {
    }

    public record Cell(String text, boolean number) {
    }

    public record Row(List<Cell> cells, boolean bold) {
    }

    public record Table(String title, String subtitle, List<String> headers, List<Boolean> numeric, List<Row> rows) {

        public boolean isEmpty() {
            return rows.isEmpty();
        }
    }

    public static Table of(Excel.Sheet sheet) {
        int columns = sheet.headers().size();
        int[] scales = Excel.scales(sheet);
        boolean[] numeric = new boolean[columns];
        List<Row> rows = new ArrayList<>();
        for (Excel.Row r : sheet.rows()) {
            List<Cell> cells = new ArrayList<>();
            for (int c = 0; c < columns; c++) {
                Object v = c < r.cells().size() ? r.cells().get(c) : null;
                boolean number = v instanceof Number;
                if (number) {
                    numeric[c] = true;
                }
                cells.add(new Cell(v instanceof BigDecimal d ? amount(d, scales[c]) : text(v), number));
            }
            rows.add(new Row(cells, r.bold()));
        }
        List<Boolean> flags = new ArrayList<>();
        for (boolean b : numeric) {
            flags.add(b);
        }
        return new Table(sheet.title(), sheet.subtitle(), sheet.headers(), flags, rows);
    }

    /** True when one of the sheets is too wide for a portrait page. */
    public static boolean landscape(Excel.Sheet... sheets) {
        for (Excel.Sheet s : sheets) {
            if (s.headers().size() > PORTRAIT_COLUMNS) {
                return true;
            }
        }
        return false;
    }

    /** The text of a cell: an amount with its own decimals (at most 4), a whole count, a date, or the value itself. */
    public static String text(Object v) {
        if (v == null) {
            return "";
        }
        if (v instanceof BigDecimal d) {
            return amount(d, d.scale());
        }
        if (v instanceof Integer || v instanceof Long) {
            return new DecimalFormat("#,##0", SYMBOLS).format(((Number) v).longValue());
        }
        if (v instanceof LocalDate d) {
            return d.format(DAY);
        }
        return v.toString();
    }

    /** An amount with {@code scale} decimals (0 to 4) and a comma between thousands. */
    public static String amount(BigDecimal d, int scale) {
        int decimals = Math.max(0, Math.min(Excel.MAX_SCALE, scale));
        DecimalFormat format = new DecimalFormat(decimals == 0 ? "#,##0" : "#,##0." + "0".repeat(decimals), SYMBOLS);
        format.setRoundingMode(RoundingMode.HALF_UP);
        return format.format(d);
    }
}
