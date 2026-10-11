package com.ntaganira.heritier.iWarehouse.config;

import java.net.URI;
import java.util.Locale;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.config
 * - File      : OversizedUploads.java
 * - Date      : 2026. 10. 11.
 * - User      : Hntaganira
 * - Desc      : A file over the upload limit (spring.servlet.multipart.max-file-size) is cut off by the server before the
 *               form is read, so its CSRF token is never seen and Spring Security refuses the request as forged. Tells
 *               that case apart from a real CSRF refusal and where to send the user back to. Pure.
 * </pre>
 */
public final class OversizedUploads {

    private OversizedUploads() {
    }

    /** A multipart request whose body is over the file limit: the reason its CSRF token could not be read. */
    public static boolean tooLarge(String contentType, long contentLength, long maxFileBytes) {
        return contentType != null && contentType.toLowerCase(Locale.ROOT).startsWith("multipart/")
                && contentLength > maxFileBytes;
    }

    /** The page the upload came from (on this server only, so it is never an open redirect), else the dashboard. */
    public static String backTo(String referer, String serverName) {
        if (referer == null || referer.isBlank()) {
            return "/dashboard";
        }
        try {
            URI uri = new URI(referer);
            if (uri.getHost() != null && !uri.getHost().equalsIgnoreCase(serverName)) {
                return "/dashboard";
            }
            String path = uri.getRawPath();
            if (path == null || !path.startsWith("/") || path.startsWith("//")) {
                return "/dashboard";
            }
            return uri.getRawQuery() == null ? path : path + "?" + uri.getRawQuery();
        } catch (Exception e) {
            return "/dashboard";
        }
    }

    /** Bytes as megabytes with one decimal, e.g. 7.3 MB, for the activity log. */
    public static String megabytes(long bytes) {
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }
}
