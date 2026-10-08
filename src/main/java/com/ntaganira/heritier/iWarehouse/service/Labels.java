package com.ntaganira.heritier.iWarehouse.service;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : Labels.java
 * - Date      : 2026. 10. 08.
 * - User      : Hntaganira
 * - Desc      : Stock unit labels (INV-03): a QR code of the unit's label code as inline SVG for the
 *               printable label page, and ZPL for Zebra-type label printers (50 x 30 mm, 203 dpi).
 *               Pure functions, unit-tested.
 * </pre>
 */
public final class Labels {

    /** 50 x 30 mm at 203 dpi (8 dots per mm). */
    static final int LABEL_WIDTH_DOTS = 400;
    static final int LABEL_HEIGHT_DOTS = 240;

    private Labels() {
    }

    /** What one label shows: code (in the QR too), product code, thickness, size and crate. */
    public record Label(String code, String productCode, String thickness, int widthMm, int heightMm, String crate) {
    }

    /**
     * QR code as an SVG drawing one square per dark module (no margin; the label leaves white space).
     * Error correction M survives a scratched label.
     */
    public static String qrSvg(String text) {
        BitMatrix matrix = qrMatrix(text);
        StringBuilder path = new StringBuilder();
        for (int y = 0; y < matrix.getHeight(); y++) {
            for (int x = 0; x < matrix.getWidth(); x++) {
                if (matrix.get(x, y)) {
                    path.append('M').append(x).append(' ').append(y).append("h1v1h-1z");
                }
            }
        }
        return "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 " + matrix.getWidth() + " " + matrix.getHeight()
                + "\" shape-rendering=\"crispEdges\" role=\"img\" aria-label=\"" + escapeXml(text) + "\">"
                + "<path fill=\"#000\" d=\"" + path + "\"/></svg>";
    }

    static BitMatrix qrMatrix(String text) {
        try {
            return new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, Map.of(
                    EncodeHintType.MARGIN, 0,
                    EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M,
                    EncodeHintType.CHARACTER_SET, StandardCharsets.UTF_8.name()));
        } catch (WriterException e) {
            throw new IllegalArgumentException("Cannot encode a QR code for " + text, e);
        }
    }

    /** One ZPL label per unit: QR on the left, code, product, size and crate on the right. */
    public static String zpl(List<Label> labels) {
        StringBuilder zpl = new StringBuilder();
        for (Label l : labels) {
            zpl.append("^XA\n")
                    .append("^CI28\n")
                    .append("^PW").append(LABEL_WIDTH_DOTS).append('\n')
                    .append("^LL").append(LABEL_HEIGHT_DOTS).append('\n')
                    .append("^FO16,30^BQN,2,5^FDMA,").append(field(l.code())).append("^FS\n")
                    .append("^FO150,28^A0N,34,28^FD").append(field(l.code())).append("^FS\n")
                    .append("^FO150,78^A0N,28,24^FD").append(field(l.productCode() + "  " + l.thickness() + " mm")).append("^FS\n")
                    .append("^FO150,116^A0N,28,24^FD").append(l.widthMm()).append(" x ").append(l.heightMm()).append(" mm^FS\n");
            if (l.crate() != null && !l.crate().isBlank()) {
                zpl.append("^FO150,154^A0N,22,20^FD").append(field(l.crate())).append("^FS\n");
            }
            zpl.append("^XZ\n");
        }
        return zpl.toString();
    }

    /** ZPL field data: ^ and ~ start commands, so they are replaced. */
    static String field(String text) {
        return text == null ? "" : text.replace('^', '-').replace('~', '-');
    }

    private static String escapeXml(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
