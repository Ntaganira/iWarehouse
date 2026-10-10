package com.ntaganira.heritier.iWarehouse.ebm;

import com.ntaganira.heritier.iWarehouse.enums.ChargeUnit;
import com.ntaganira.heritier.iWarehouse.enums.PaymentMethod;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Set;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.ebm
 * - File      : EbmCodes.java
 * - Date      : 2026. 10. 10.
 * - User      : Hntaganira
 * - Desc      : RRA's EBM codes and formats (VSDC spec section 4) as iWarehouse uses them: payment types (4.10),
 *               quantity units (4.6: glass by the m², processing by its unit), item types and item codes (4.17:
 *               origin + type + packaging + quantity unit + serial, 2-letter units padded with X as RRA's client
 *               does), refund reasons (4.16), the receipt number label (27/32 NS; a copy CS, a refund NR / CR),
 *               internal data and signature in groups of four, and what the receipt's QR code holds
 *               (TIN + branch + signature after RRA's receipt check URL). Pure, unit-tested.
 * </pre>
 */
public final class EbmCodes {

    public static final String SALE = "S";
    public static final String REFUND = "R";
    public static final String NORMAL = "N";
    public static final String COPY = "C";

    /** Sale status: approved (a sale), refunded (a refund). */
    public static final String APPROVED = "02";
    public static final String REFUNDED = "05";

    public static final String GLASS_ITEM_TYPE = "2";      // finished product
    public static final String SERVICE_ITEM_TYPE = "3";    // service, no stock
    public static final String PACKAGING = "NT";           // net: glass is not sold in packs
    public static final String GLASS_UNIT = "M2";

    /** Refund reason codes (spec 4.16), in order; 06 "Refund" is the default. */
    public static final List<String> REFUND_REASONS = List.of("01", "02", "03", "04", "05", "06", "07", "08", "09", "10", "11", "12", "13");
    public static final String DEFAULT_REFUND_REASON = "06";

    public static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    public static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private EbmCodes() {
    }

    public static String code(PaymentMethod method) {
        return switch (method) {
            case CASH -> "01";
            case CREDIT -> "02";
            case CARD -> "05";
            case MOBILE_MONEY -> "06";
            case BANK_TRANSFER -> "07";     // RRA has no transfer code (04 is a bank cheque): other
        };
    }

    /**
     * The payment type of a sale (spec 4.10): its one method, or 03 cash/credit when part stays owed (customer credit or
     * a deposit's balance), or 07 other for several methods paid in full.
     */
    public static String paymentType(Set<PaymentMethod> methods, boolean balanceDue) {
        if (methods.isEmpty()) {
            return code(PaymentMethod.CREDIT);
        }
        if (balanceDue) {
            return "03";
        }
        if (methods.size() == 1) {
            return code(methods.iterator().next());
        }
        return methods.contains(PaymentMethod.CREDIT) ? "03" : "07";
    }

    /** RRA quantity unit of processing charged per {@code unit}. */
    public static String quantityUnit(ChargeUnit unit) {
        return switch (unit) {
            case M2 -> GLASS_UNIT;
            case METRE -> "M";
            case PIECE, HOLE -> "U";
        };
    }

    /** RW2NTXM2X0000001: origin, item type, packaging and quantity units (2 letters padded with X), 7-digit serial. */
    public static String itemCode(String origin, String itemType, String packaging, String quantityUnit, long serial) {
        if (serial < 1 || serial > 9_999_999) {
            throw new IllegalArgumentException("EBM item serial out of range: " + serial);
        }
        return origin + itemType + pad(packaging) + pad(quantityUnit) + String.format("%07d", serial);
    }

    private static String pad(String unit) {
        return unit.length() == 2 ? unit + "X" : unit;
    }

    /** The receipt number line: 27/32 NS (receipt / total receipts, then sale type and receipt type). */
    public static String receiptLabel(long rcptNo, long totRcptNo, boolean copy, String receiptType) {
        return rcptNo + "/" + totRcptNo + " " + (copy ? COPY : NORMAL) + receiptType;
    }

    /** 2ZQS-U6NW-7NYF-MLWN-ZFHR-6FF5-AQ, as RRA prints internal data and signatures. */
    public static String groups(String text) {
        if (text == null) {
            return null;
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < text.length(); i += 4) {
            if (i > 0) {
                out.append('-');
            }
            out.append(text, i, Math.min(i + 4, text.length()));
        }
        return out.toString();
    }

    /** What the receipt's QR code holds: RRA's receipt check followed by TIN + branch + signature. */
    public static String qrData(String receiptUrl, String tin, String branchId, String signature) {
        return receiptUrl + tin + branchId + signature;
    }

    /** RFC 4648 Base32 without padding, as the VSDC writes internal data and signatures. */
    public static String base32(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        int buffer = 0;
        int bits = 0;
        for (byte b : bytes) {
            buffer = (buffer << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5) {
                out.append(BASE32.charAt((buffer >> (bits - 5)) & 31));
                bits -= 5;
            }
        }
        if (bits > 0) {
            out.append(BASE32.charAt((buffer << (5 - bits)) & 31));
        }
        return out.toString();
    }

    public static String dateTime(LocalDateTime at) {
        return at == null ? null : at.format(DATE_TIME);
    }

    public static String date(LocalDate day) {
        return day == null ? null : day.format(DATE);
    }

    /** A VSDC date-time, or null when it is missing or not yyyyMMddHHmmss. */
    public static LocalDateTime parseDateTime(String text) {
        if (text == null || !text.matches("\\d{14}")) {
            return null;
        }
        try {
            return LocalDateTime.parse(text, DATE_TIME);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** Text cut to what a VSDC field holds. */
    public static String cut(String text, int max) {
        if (text == null) {
            return null;
        }
        String t = text.strip();
        return t.isEmpty() ? null : t.length() <= max ? t : t.substring(0, max);
    }
}
