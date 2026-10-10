package com.ntaganira.heritier.iWarehouse.service;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : Documents.java
 * - Date      : 2026. 10. 10.
 * - User      : Hntaganira
 * - Desc      : What a document may be (SRS 3.1 file storage): a PDF or a photo (JPEG, PNG, WebP), told by its first bytes
 *               (never by the name or the type the browser sends), at most 5 MB; its name is kept without any path and
 *               without control characters, at most 200 long. Pure, unit-tested.
 * </pre>
 */
public final class Documents {

    /** The largest document kept: 5 MB (spring.servlet.multipart.max-file-size). */
    public static final long MAX_BYTES = 5L * 1024 * 1024;

    private Documents() {
    }

    /** The type of the content from its first bytes, or null when it is no PDF, JPEG, PNG or WebP. */
    public static String detect(byte[] b) {
        if (b == null || b.length < 4) {
            return null;
        }
        if (b[0] == '%' && b[1] == 'P' && b[2] == 'D' && b[3] == 'F') {
            return "application/pdf";
        }
        if ((b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        if ((b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') {
            return "image/png";
        }
        if (b.length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F' && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') {
            return "image/webp";
        }
        return null;
    }

    /** The extension a stored object gets for its type. */
    public static String extension(String contentType) {
        return switch (contentType) {
            case "application/pdf" -> ".pdf";
            case "image/jpeg" -> ".jpg";
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            default -> "";
        };
    }

    /** The name as sent, without folders or control characters, at most 200 long; "document" when nothing is left. */
    public static String cleanName(String original) {
        if (original == null) {
            return "document";
        }
        String name = original.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}\"]", "").trim();
        if (name.isEmpty()) {
            return "document";
        }
        return name.length() > 200 ? name.substring(name.length() - 200) : name;
    }
}
