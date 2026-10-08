package com.ntaganira.heritier.iWarehouse.config;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.config
 * - File      : NumberFormats.java
 * - Date      : 2026. 10. 07.
 * - User      : Hntaganira
 * - Desc      : Number formats Thymeleaf's #numbers can't express, used in templates as ${@num.rate(x)}.
 *               Same separators as the other screens (1,450.25), whatever the language.
 * </pre>
 */
@Component("num")
public class NumberFormats {

    private static final DecimalFormatSymbols SYMBOLS = DecimalFormatSymbols.getInstance(Locale.US);

    /** Exchange rate with 2 to 6 decimals: 1,450.25 or 196.123456. */
    public String rate(BigDecimal rate) {
        return rate == null ? "" : format("#,##0.00####", rate);
    }

    /** Signed percentage with 2 decimals: +1.25 or -0.40. */
    public String signedPercent(BigDecimal percent) {
        if (percent == null) {
            return "";
        }
        return (percent.signum() > 0 ? "+" : "") + format("#,##0.00", percent);
    }

    /** RWF amount: no decimals unless it has some (25,000 or 12,500.50). */
    public String money(BigDecimal amount) {
        if (amount == null) {
            return "";
        }
        return format(amount.stripTrailingZeros().scale() > 0 ? "#,##0.00" : "#,##0", amount);
    }

    /** The same, from a change-log snapshot value ("25000.5"); the text itself if it is not a number. */
    public String moneyText(String amount) {
        if (amount == null || amount.isBlank()) {
            return "";
        }
        try {
            return money(new BigDecimal(amount));
        } catch (NumberFormatException e) {
            return amount;
        }
    }

    /** Amount in a document's currency with that currency's decimals: 1,234.50 USD, 1,234 RWF. */
    public String amount(BigDecimal amount, int decimals) {
        if (amount == null) {
            return "";
        }
        return format(decimals <= 0 ? "#,##0" : "#,##0." + "0".repeat(decimals), amount);
    }

    /** Price or cost per m² with 2 to 4 decimals: 4.35, 4.1250, 27,000.00. */
    public String price(BigDecimal price) {
        return price == null ? "" : format("#,##0.00##", price);
    }

    /** Area in m² with up to 4 decimals: 7.2225, 144.45. */
    public String m2(BigDecimal area) {
        return area == null ? "" : format("#,##0.####", area);
    }

    /** Weight in kg with up to 2 decimals: 108.34, 2,167. */
    public String kg(BigDecimal kg) {
        return kg == null ? "" : format("#,##0.##", kg);
    }

    /** A price as typed in an input: 25000 or 12500.5 (no grouping, no trailing zeros). */
    public String plain(BigDecimal amount) {
        return amount == null ? "" : amount.stripTrailingZeros().toPlainString();
    }

    private static String format(String pattern, BigDecimal value) {
        DecimalFormat format = new DecimalFormat(pattern, SYMBOLS); // not thread-safe: one per call
        format.setRoundingMode(RoundingMode.HALF_UP);
        return format.format(value);
    }
}
