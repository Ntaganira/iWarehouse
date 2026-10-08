package com.ntaganira.heritier.iWarehouse.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : RateCsv.java
 * - Date      : 2026. 10. 07.
 * - User      : Hntaganira
 * - Desc      : Reads an exchange rate file (ACC-02): one "date,currency,rate" line per rate, as typed
 *               or exported from Excel. Accepts comma, semicolon or tab separators, an optional header,
 *               dates as 2026-10-07 or 07/10/2026, and 1450.25, 1,450.25 or 1 450,25 as the rate.
 *               Pure parsing: checks against the database happen in ExchangeRateService.
 * </pre>
 */
public final class RateCsv {

    /** One readable line of the file. */
    public record Line(int number, LocalDate date, String currencyCode, BigDecimal rate) {
    }

    /** A line that could not be used, with a message key and its arguments. Line 0 = the whole file. */
    public record Problem(int line, String messageKey, Object... args) {
    }

    public record Parsed(List<Line> lines, List<Problem> problems) {
    }

    private static final Pattern CODE = Pattern.compile("^[A-Z]{3}$");
    private static final List<DateTimeFormatter> DATES = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("dd/MM/uuuu"),
            DateTimeFormatter.ofPattern("dd.MM.uuuu"));

    private RateCsv() {
    }

    public static Parsed parse(String content, int maxLines) {
        List<Line> lines = new ArrayList<>();
        List<Problem> problems = new ArrayList<>();
        String[] rows = content.replace("﻿", "").split("\\r?\\n|\\r", -1);
        boolean firstData = true;
        for (int i = 0; i < rows.length; i++) {
            int number = i + 1;
            String text = rows[i].strip();
            if (text.isEmpty() || text.startsWith("#")) {
                continue;
            }
            if (firstData && !Character.isDigit(text.charAt(0))) {
                firstData = false; // header such as "date,currency,rate"
                continue;
            }
            firstData = false;

            char separator = text.indexOf(';') >= 0 ? ';' : text.indexOf('\t') >= 0 ? '\t' : ',';
            List<String> cells = split(text, separator);
            if (cells.size() < 3) {
                problems.add(new Problem(number, "rate.import.columns"));
                continue;
            }
            LocalDate date = parseDate(cells.get(0));
            String code = cells.get(1).toUpperCase(Locale.ROOT);
            BigDecimal rate = parseRate(cells.get(2), separator == ';');
            if (date == null) {
                problems.add(new Problem(number, "rate.import.date", cells.get(0)));
            } else if (!CODE.matcher(code).matches()) {
                problems.add(new Problem(number, "rate.import.currency", cells.get(1)));
            } else if (rate == null) {
                problems.add(new Problem(number, "rate.import.rate", cells.get(2)));
            } else {
                lines.add(new Line(number, date, code, rate));
            }
        }
        if (lines.size() > maxLines) {
            problems.add(0, new Problem(0, "rate.import.tooMany", maxLines));
        }
        if (lines.isEmpty() && problems.isEmpty()) {
            problems.add(new Problem(0, "rate.import.empty"));
        }
        return new Parsed(lines, problems);
    }

    /** Splits on the separator, honouring double quotes ("1,450.25"). */
    static List<String> split(String text, char separator) {
        List<String> cells = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (char c : text.toCharArray()) {
            if (c == '"') {
                quoted = !quoted;
            } else if (c == separator && !quoted) {
                cells.add(cell.toString().strip());
                cell.setLength(0);
            } else {
                cell.append(c);
            }
        }
        cells.add(cell.toString().strip());
        return cells;
    }

    static LocalDate parseDate(String text) {
        for (DateTimeFormatter format : DATES) {
            try {
                return LocalDate.parse(text.strip(), format);
            } catch (DateTimeParseException ignored) {
                // try the next format
            }
        }
        return null;
    }

    /**
     * Reads a positive rate with at most 12 integer digits and 6 decimals. When both '.' and ','
     * appear, the last one is the decimal mark. A lone ',' is a decimal mark only in semicolon files
     * (French Excel); in comma files it can only be a thousands separator inside quotes.
     */
    static BigDecimal parseRate(String text, boolean decimalComma) {
        String s = text.replace(" ", "").replace(" ", "").replace(" ", "");
        int dot = s.lastIndexOf('.');
        int comma = s.lastIndexOf(',');
        if (dot >= 0 && comma >= 0) {
            s = comma > dot ? s.replace(".", "").replace(',', '.') : s.replace(",", "");
        } else if (comma >= 0) {
            s = decimalComma ? s.replace(',', '.') : s.replace(",", "");
        }
        try {
            BigDecimal rate = new BigDecimal(s);
            if (rate.signum() <= 0 || rate.scale() > CurrencyMath.RATE_SCALE || rate.precision() - rate.scale() > 12) {
                return null;
            }
            return rate;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
