package com.ntaganira.heritier.iWarehouse.service;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : Excel.java
 * - Date      : 2026. 10. 09.
 * - User      : Hntaganira
 * - Desc      : Excel export of reports (ACC-11, TAX-05): one sheet per table, a title, a line saying what it covers,
 *               the column headers and the rows. Amounts are numbers so the accountant can add them up, each column
 *               shown with the most decimals of its amounts (money 2, m² 4: #,##0.00, #,##0.0000); dates are dates (dd/mm/yyyy); total and
 *               section rows are bold. The PDF export prints the same sheets (PrintTable). Pure apart from Apache POI.
 * </pre>
 */
public final class Excel {

    public static final String CONTENT_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    /** Amounts show at most this many decimals (m²). */
    public static final int MAX_SCALE = 4;

    private Excel() {
    }

    /** A row: its cells (String, BigDecimal, Integer/Long, LocalDate or null) and whether it is bold (a total, a section). */
    public record Row(List<Object> cells, boolean bold) {

        public static Row of(Object... cells) {
            return new Row(Arrays.asList(cells), false);
        }

        public static Row bold(Object... cells) {
            return new Row(Arrays.asList(cells), true);
        }
    }

    /** A sheet: its tab name, title, what it covers, the headers and the rows. */
    public record Sheet(String name, String title, String subtitle, List<String> headers, List<Row> rows) {
    }

    /** Builds a sheet as rows are added. */
    public static final class Builder {
        private final String name;
        private final String title;
        private final String subtitle;
        private final List<String> headers;
        private final List<Row> rows = new ArrayList<>();

        private Builder(String name, String title, String subtitle, List<String> headers) {
            this.name = name;
            this.title = title;
            this.subtitle = subtitle;
            this.headers = headers;
        }

        public Builder row(Object... cells) {
            rows.add(Row.of(cells));
            return this;
        }

        public Builder bold(Object... cells) {
            rows.add(Row.bold(cells));
            return this;
        }

        public Sheet build() {
            return new Sheet(name, title, subtitle, headers, rows);
        }
    }

    public static Builder sheet(String name, String title, String subtitle, String... headers) {
        return new Builder(name, title, subtitle, List.of(headers));
    }

    /** The workbook's bytes (.xlsx). */
    public static byte[] write(Sheet... sheets) {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Styles st = new Styles(wb);
            for (Sheet sheet : sheets) {
                write(wb, st, sheet);
            }
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Per column, the most decimals of its amounts (0 to MAX_SCALE): a column shows them all alike. */
    public static int[] scales(Sheet sheet) {
        int[] scales = new int[Math.max(sheet.headers().size(), 1)];
        for (Row row : sheet.rows()) {
            for (int c = 0; c < row.cells().size() && c < scales.length; c++) {
                if (row.cells().get(c) instanceof BigDecimal d) {
                    scales[c] = Math.max(scales[c], Math.max(0, Math.min(MAX_SCALE, d.scale())));
                }
            }
        }
        return scales;
    }

    private static void write(Workbook wb, Styles st, Sheet sheet) {
        org.apache.poi.ss.usermodel.Sheet s = wb.createSheet(safeName(sheet.name()));
        int[] scales = scales(sheet);
        int[] widths = new int[Math.max(sheet.headers().size(), 1)];
        int r = 0;
        Cell title = s.createRow(r++).createCell(0);
        title.setCellValue(sheet.title());
        title.setCellStyle(st.title);
        if (sheet.subtitle() != null) {
            s.createRow(r++).createCell(0).setCellValue(sheet.subtitle());
        }
        r++;
        org.apache.poi.ss.usermodel.Row header = s.createRow(r++);
        for (int c = 0; c < sheet.headers().size(); c++) {
            Cell cell = header.createCell(c);
            cell.setCellValue(sheet.headers().get(c));
            cell.setCellStyle(st.header);
            widths[c] = Math.max(widths[c], sheet.headers().get(c).length());
        }
        for (Row row : sheet.rows()) {
            org.apache.poi.ss.usermodel.Row x = s.createRow(r++);
            for (int c = 0; c < row.cells().size(); c++) {
                Object v = row.cells().get(c);
                if (v == null) {
                    continue;
                }
                Cell cell = x.createCell(c);
                if (v instanceof BigDecimal d) {
                    cell.setCellValue(d.doubleValue());
                    cell.setCellStyle(st.amount(c < scales.length ? scales[c] : d.scale(), row.bold()));
                    widen(widths, c, 14);
                } else if (v instanceof Integer || v instanceof Long) {
                    cell.setCellValue(((Number) v).doubleValue());
                    cell.setCellStyle(row.bold() ? st.integerBold : st.integer);
                    widen(widths, c, 8);
                } else if (v instanceof LocalDate d) {
                    cell.setCellValue(d);
                    cell.setCellStyle(st.date);
                    widen(widths, c, 11);
                } else {
                    cell.setCellValue(v.toString());
                    if (row.bold()) {
                        cell.setCellStyle(st.bold);
                    }
                    widen(widths, c, Math.min(v.toString().length(), 60));
                }
            }
        }
        for (int c = 0; c < widths.length; c++) {
            s.setColumnWidth(c, Math.min(Math.max(widths[c] + 2, 8), 62) * 256);
        }
        s.createFreezePane(0, sheet.subtitle() == null ? 3 : 4);
    }

    private static void widen(int[] widths, int column, int chars) {
        if (column < widths.length) {
            widths[column] = Math.max(widths[column], chars);
        }
    }

    /** A sheet name without the characters Excel refuses, at most 31 long. */
    private static String safeName(String name) {
        String clean = name.replaceAll("[\\\\/?*\\[\\]:]", " ").trim();
        return clean.length() > 31 ? clean.substring(0, 31) : clean;
    }

    private static final class Styles {
        final CellStyle title;
        final CellStyle header;
        final CellStyle bold;
        final CellStyle[] amounts = new CellStyle[(MAX_SCALE + 1) * 2];
        final CellStyle integer;
        final CellStyle integerBold;
        final CellStyle date;

        Styles(Workbook wb) {
            DataFormat format = wb.createDataFormat();
            Font boldFont = wb.createFont();
            boldFont.setBold(true);
            Font titleFont = wb.createFont();
            titleFont.setBold(true);
            titleFont.setFontHeightInPoints((short) 14);
            title = wb.createCellStyle();
            title.setFont(titleFont);
            header = wb.createCellStyle();
            header.setFont(boldFont);
            header.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            header.setBorderBottom(BorderStyle.THIN);
            bold = wb.createCellStyle();
            bold.setFont(boldFont);
            for (int scale = 0; scale <= MAX_SCALE; scale++) {
                CellStyle plain = wb.createCellStyle();
                plain.setDataFormat(format.getFormat(scale == 0 ? "#,##0" : "#,##0." + "0".repeat(scale)));
                CellStyle strong = wb.createCellStyle();
                strong.cloneStyleFrom(plain);
                strong.setFont(boldFont);
                amounts[scale * 2] = plain;
                amounts[scale * 2 + 1] = strong;
            }
            integer = wb.createCellStyle();
            integer.setDataFormat(format.getFormat("#,##0"));
            integerBold = wb.createCellStyle();
            integerBold.cloneStyleFrom(integer);
            integerBold.setFont(boldFont);
            date = wb.createCellStyle();
            date.setDataFormat(format.getFormat("dd/mm/yyyy"));
        }

        /** An amount's style: its own decimals (0 to 4), bold or not. */
        CellStyle amount(int scale, boolean bold) {
            return amounts[Math.max(0, Math.min(MAX_SCALE, scale)) * 2 + (bold ? 1 : 0)];
        }
    }
}
